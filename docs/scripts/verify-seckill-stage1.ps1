param(
    [string]$JMeterPath = "C:\Program Files\apache-jmeter-5.6.3\bin\jmeter.bat",
    [string]$MysqlCliPath = "mysql",
    [string]$RedisCliPath = "redis-cli",
    [string]$DbHost = "localhost",
    [int]$DbPort = 2881,
    [string]$DbName = "mall",
    [string]$DbUser = "root@test",
    [string]$DbPassword = "",
    [string]$ServiceHost = "localhost",
    [int]$ServicePort = 8105,
    [string]$RedisHost = "localhost",
    [int]$RedisPort = 6381,
    [long]$ActivityId = 1,
    [long]$SkuId = 1001,
    [int]$Stock = 1000,
    [int]$Threads = 200,
    [int]$Loops = 5,
    [int]$Ramp = 2,
    [long]$UserIdStart = 1000000,
    [int]$WaitSeconds = 60,
    [int]$ExpectedSuccess = -1,
    [int]$MaxJMeterFailures = 0
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$loadtestRoot = Join-Path $repoRoot "target\loadtest"
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$jtlPath = Join-Path $loadtestRoot "jmeter-seckill-stage1-$timestamp.jtl"
$reportDir = Join-Path $loadtestRoot "jmeter-report-seckill-stage1-$timestamp"
$summaryPath = Join-Path $loadtestRoot "stage1-verify-$timestamp.json"
$jmxPath = Join-Path $repoRoot "docs\jmeter\seckill-submit.jmx"
$cacheKey = "seckill:stock-cache:$ActivityId`:$SkuId"

if ($ExpectedSuccess -lt 0) {
    $ExpectedSuccess = $Threads * $Loops
}

function Invoke-MysqlQuery {
    param([string]$Sql)

    $args = @(
        "--host=$DbHost",
        "--port=$DbPort",
        "--user=$DbUser",
        "--database=$DbName",
        "--default-character-set=utf8mb4",
        "--batch",
        "--raw",
        "--skip-column-names",
        "--execute=$Sql"
    )
    if ($DbPassword -ne "") {
        $args += "--password=$DbPassword"
    }
    $output = & $MysqlCliPath @args
    if ($LASTEXITCODE -ne 0) {
        throw "mysql command failed with exit code $LASTEXITCODE"
    }
    return ($output -join "`n").Trim()
}

function Invoke-MysqlScalar {
    param([string]$Sql)

    $value = Invoke-MysqlQuery -Sql $Sql
    if ($value -eq "") {
        return $null
    }
    return ($value -split "`n")[0].Trim()
}

function Remove-ReportDirectory {
    param([string]$Path)

    $targetRoot = [System.IO.Path]::GetFullPath($loadtestRoot)
    $fullPath = [System.IO.Path]::GetFullPath($Path)
    if (-not $fullPath.StartsWith($targetRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to remove path outside target loadtest directory: $fullPath"
    }
    if (Test-Path -LiteralPath $fullPath) {
        Remove-Item -LiteralPath $fullPath -Recurse -Force
    }
}

function Read-JMeterFailures {
    param([string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        throw "JMeter result file not found: $Path"
    }
    $rows = Import-Csv -LiteralPath $Path
    $failures = @($rows | Where-Object { $_.success -ne "true" })
    return $failures.Count
}

function Read-TairStringValue {
    param([string]$Key)

    $output = & $RedisCliPath -h $RedisHost -p $RedisPort --raw EXGET $Key
    if ($LASTEXITCODE -ne 0) {
        throw "redis-cli EXGET failed with exit code $LASTEXITCODE"
    }
    $parts = @($output | Where-Object { $_ -ne $null -and $_.ToString().Trim() -ne "" })
    if ($parts.Count -lt 2) {
        return @{ value = $null; version = $null }
    }
    return @{
        value = [int]$parts[0]
        version = [long]$parts[1]
    }
}

New-Item -ItemType Directory -Force -Path $loadtestRoot | Out-Null
Remove-ReportDirectory -Path $reportDir

Write-Host "Resetting seckill stage1 data..."
Invoke-MysqlQuery -Sql @"
DELETE FROM consume_record;
DELETE FROM mq_message;
DELETE FROM seckill_order WHERE activity_id = $ActivityId;
DELETE FROM seckill_result;
DELETE FROM seckill_stock_snapshot;
UPDATE seckill_sku
SET stock = $Stock, version = 0
WHERE activity_id = $ActivityId AND sku_id = $SkuId;
"@ | Out-Null

& $RedisCliPath -h $RedisHost -p $RedisPort DEL $cacheKey | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "redis-cli DEL failed with exit code $LASTEXITCODE"
}

Write-Host "Running JMeter stage1 submit test..."
$jmeterArgs = @(
    "-n",
    "-t", $jmxPath,
    "-Jhost=$ServiceHost",
    "-Jport=$ServicePort",
    "-JactivityId=$ActivityId",
    "-JskuId=$SkuId",
    "-Jthreads=$Threads",
    "-Jloops=$Loops",
    "-Jramp=$Ramp",
    "-JuserIdStart=$UserIdStart",
    "-l", $jtlPath,
    "-e",
    "-o", $reportDir
)
& $JMeterPath @jmeterArgs
if ($LASTEXITCODE -ne 0) {
    throw "JMeter failed with exit code $LASTEXITCODE"
}

$deadline = (Get-Date).AddSeconds($WaitSeconds)
do {
    Start-Sleep -Seconds 2
    $confirmed = [int](Invoke-MysqlScalar -Sql "SELECT COUNT(*) FROM seckill_stock_snapshot WHERE status = 'CONFIRMED'")
    $deducted = [int](Invoke-MysqlScalar -Sql "SELECT COUNT(*) FROM seckill_stock_snapshot WHERE status = 'DEDUCTED'")
    if ($confirmed -ge $ExpectedSuccess -and $deducted -eq 0) {
        break
    }
} while ((Get-Date) -lt $deadline)

$skuRow = Invoke-MysqlQuery -Sql "SELECT stock, version FROM seckill_sku WHERE activity_id = $ActivityId AND sku_id = $SkuId"
$skuParts = $skuRow -split "`t"
$dbStock = [int]$skuParts[0]
$dbVersion = [long]$skuParts[1]
$confirmed = [int](Invoke-MysqlScalar -Sql "SELECT COUNT(*) FROM seckill_stock_snapshot WHERE status = 'CONFIRMED'")
$deducted = [int](Invoke-MysqlScalar -Sql "SELECT COUNT(*) FROM seckill_stock_snapshot WHERE status = 'DEDUCTED'")
$released = [int](Invoke-MysqlScalar -Sql "SELECT COUNT(*) FROM seckill_stock_snapshot WHERE status = 'RELEASED'")
$successResults = [int](Invoke-MysqlScalar -Sql "SELECT COUNT(*) FROM seckill_result WHERE status = 'SUCCESS'")
$failedResults = [int](Invoke-MysqlScalar -Sql "SELECT COUNT(*) FROM seckill_result WHERE status = 'FAILED'")
$orders = [int](Invoke-MysqlScalar -Sql "SELECT COUNT(*) FROM seckill_order WHERE activity_id = $ActivityId")
$seckillMqOpen = [int](Invoke-MysqlScalar -Sql "SELECT COUNT(*) FROM mq_message WHERE routing_key IN ('seckill.order.create', 'seckill.order.result') AND status IN ('NEW', 'DISPATCHING', 'FAILED')")
$jmeterFailures = Read-JMeterFailures -Path $jtlPath
$cache = Read-TairStringValue -Key $cacheKey

$expectedStock = $Stock - $ExpectedSuccess
$checks = [ordered]@{
    jmeterFailures = ($jmeterFailures -le $MaxJMeterFailures)
    stock = ($dbStock -eq $expectedStock)
    version = ($dbVersion -eq $ExpectedSuccess)
    confirmed = ($confirmed -eq $ExpectedSuccess)
    deductedCleared = ($deducted -eq 0)
    results = ($successResults -eq $ExpectedSuccess -and $failedResults -eq 0)
    orders = ($orders -eq $ExpectedSuccess)
    seckillMessagesDrained = ($seckillMqOpen -eq 0)
    cache = ($cache.value -eq $dbStock -and $cache.version -eq $dbVersion)
}
$passed = -not ($checks.Values -contains $false)

$summary = [ordered]@{
    passed = $passed
    timestamp = $timestamp
    activityId = $ActivityId
    skuId = $SkuId
    expectedSuccess = $ExpectedSuccess
    jmeter = [ordered]@{
        result = $jtlPath
        report = $reportDir
        failures = $jmeterFailures
        maxFailures = $MaxJMeterFailures
    }
    database = [ordered]@{
        stock = $dbStock
        version = $dbVersion
        confirmed = $confirmed
        deducted = $deducted
        released = $released
        successResults = $successResults
        failedResults = $failedResults
        seckillOrders = $orders
        openSeckillMessages = $seckillMqOpen
    }
    cache = $cache
    checks = $checks
}

$summary | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $summaryPath -Encoding UTF8
Write-Host "Stage1 verification summary: $summaryPath"
$summary | ConvertTo-Json -Depth 6

if (-not $passed) {
    exit 1
}
