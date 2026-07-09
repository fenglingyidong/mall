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
    [long]$UserIdStart = 2000000,
    [int]$HotspotThreads = 200,
    [int]$HotspotLoops = 2,
    [int]$HotspotRamp = 2,
    [long]$HotspotUserIdStart = 3000000,
    [int]$WaitSeconds = 90,
    [int]$MinHotspotFailures = 0,
    [int]$MaxServerErrors = 0
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$loadtestRoot = Join-Path $repoRoot "target\loadtest"
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$summaryPath = Join-Path $loadtestRoot "stage2-verify-$timestamp.json"
$jmxPath = Join-Path $repoRoot "docs\jmeter\seckill-submit.jmx"
$cacheKey = "seckill:stock-cache:$ActivityId`:$SkuId"

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

function Reset-SeckillData {
    param([int]$InitialStock)

    Invoke-MysqlQuery -Sql @"
DELETE FROM consume_record;
DELETE FROM mq_message;
DELETE FROM seckill_order WHERE activity_id = $ActivityId;
DELETE FROM seckill_result;
DELETE FROM seckill_stock_snapshot;
UPDATE seckill_sku
SET stock = $InitialStock, version = 0
WHERE activity_id = $ActivityId AND sku_id = $SkuId;
"@ | Out-Null

    & $RedisCliPath -h $RedisHost -p $RedisPort DEL $cacheKey | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "redis-cli DEL failed with exit code $LASTEXITCODE"
    }
}

function Invoke-JMeterScenario {
    param(
        [string]$Name,
        [int]$ScenarioThreads,
        [int]$ScenarioLoops,
        [int]$ScenarioRamp,
        [long]$ScenarioUserIdStart
    )

    $jtlPath = Join-Path $loadtestRoot "jmeter-$Name-$timestamp.jtl"
    $reportDir = Join-Path $loadtestRoot "jmeter-report-$Name-$timestamp"
    Remove-ReportDirectory -Path $reportDir

    $jmeterArgs = @(
        "-n",
        "-t", $jmxPath,
        "-Jhost=$ServiceHost",
        "-Jport=$ServicePort",
        "-JactivityId=$ActivityId",
        "-JskuId=$SkuId",
        "-Jthreads=$ScenarioThreads",
        "-Jloops=$ScenarioLoops",
        "-Jramp=$ScenarioRamp",
        "-JuserIdStart=$ScenarioUserIdStart",
        "-l", $jtlPath,
        "-e",
        "-o", $reportDir
    )
    & $JMeterPath @jmeterArgs
    if ($LASTEXITCODE -ne 0) {
        throw "JMeter scenario $Name failed with exit code $LASTEXITCODE"
    }

    return Read-JMeterMetrics -Path $jtlPath -ReportDir $reportDir
}

function Test-ServerErrorResponse {
    param([string]$ResponseCode)

    $parsed = 0
    if (-not [int]::TryParse($ResponseCode, [ref]$parsed)) {
        return $false
    }
    return $parsed -ge 500
}

function Read-JMeterMetrics {
    param([string]$Path, [string]$ReportDir)

    if (-not (Test-Path -LiteralPath $Path)) {
        throw "JMeter result file not found: $Path"
    }
    $rows = @(Import-Csv -LiteralPath $Path)
    if ($rows.Count -eq 0) {
        throw "JMeter result file is empty: $Path"
    }
    $elapsed = @($rows | ForEach-Object { [int]$_.elapsed } | Sort-Object)
    $timestamps = @($rows | ForEach-Object { [long]$_.timeStamp })
    $endTimes = @($rows | ForEach-Object { [long]$_.timeStamp + [int]$_.elapsed })
    $durationSeconds = [math]::Max(0.001, (($endTimes | Measure-Object -Maximum).Maximum - ($timestamps | Measure-Object -Minimum).Minimum) / 1000.0)
    $failures = @($rows | Where-Object { $_.success -ne "true" })
    $serverErrors = @($rows | Where-Object { Test-ServerErrorResponse -ResponseCode $_.responseCode })

    return [ordered]@{
        result = $Path
        report = $ReportDir
        requests = $rows.Count
        failures = $failures.Count
        serverErrors = $serverErrors.Count
        qps = [math]::Round($rows.Count / $durationSeconds, 2)
        durationSeconds = [math]::Round($durationSeconds, 3)
        avgMs = [math]::Round((($elapsed | Measure-Object -Average).Average), 2)
        p95Ms = $elapsed[[math]::Ceiling($elapsed.Count * 0.95) - 1]
        p99Ms = $elapsed[[math]::Ceiling($elapsed.Count * 0.99) - 1]
    }
}

function Wait-SeckillLedger {
    param([int]$MinimumConfirmed)

    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    do {
        Start-Sleep -Seconds 2
        $confirmed = [int](Invoke-MysqlScalar -Sql "SELECT COUNT(*) FROM seckill_stock_snapshot WHERE status = 'CONFIRMED'")
        $deducted = [int](Invoke-MysqlScalar -Sql "SELECT COUNT(*) FROM seckill_stock_snapshot WHERE status = 'DEDUCTED'")
        $openMessages = [int](Invoke-MysqlScalar -Sql "SELECT COUNT(*) FROM mq_message WHERE routing_key IN ('seckill.order.create', 'seckill.order.result') AND status IN ('NEW', 'DISPATCHING', 'FAILED')")
        if ($confirmed -ge $MinimumConfirmed -and $deducted -eq 0 -and $openMessages -eq 0) {
            break
        }
    } while ((Get-Date) -lt $deadline)
}

function Read-Ledger {
    $skuRow = Invoke-MysqlQuery -Sql "SELECT stock, version FROM seckill_sku WHERE activity_id = $ActivityId AND sku_id = $SkuId"
    $skuParts = $skuRow -split "`t"
    return [ordered]@{
        stock = [int]$skuParts[0]
        version = [long]$skuParts[1]
        confirmed = [int](Invoke-MysqlScalar -Sql "SELECT COUNT(*) FROM seckill_stock_snapshot WHERE status = 'CONFIRMED'")
        deducted = [int](Invoke-MysqlScalar -Sql "SELECT COUNT(*) FROM seckill_stock_snapshot WHERE status = 'DEDUCTED'")
        released = [int](Invoke-MysqlScalar -Sql "SELECT COUNT(*) FROM seckill_stock_snapshot WHERE status = 'RELEASED'")
        successResults = [int](Invoke-MysqlScalar -Sql "SELECT COUNT(*) FROM seckill_result WHERE status = 'SUCCESS'")
        failedResults = [int](Invoke-MysqlScalar -Sql "SELECT COUNT(*) FROM seckill_result WHERE status = 'FAILED'")
        seckillOrders = [int](Invoke-MysqlScalar -Sql "SELECT COUNT(*) FROM seckill_order WHERE activity_id = $ActivityId")
        openSeckillMessages = [int](Invoke-MysqlScalar -Sql "SELECT COUNT(*) FROM mq_message WHERE routing_key IN ('seckill.order.create', 'seckill.order.result') AND status IN ('NEW', 'DISPATCHING', 'FAILED')")
    }
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

Write-Host "Running stage2 main ledger scenario..."
$mainExpectedSuccess = $Threads * $Loops
Reset-SeckillData -InitialStock $Stock
$mainJMeter = Invoke-JMeterScenario -Name "seckill-stage2-main" -ScenarioThreads $Threads -ScenarioLoops $Loops -ScenarioRamp $Ramp -ScenarioUserIdStart $UserIdStart
Wait-SeckillLedger -MinimumConfirmed $mainExpectedSuccess
$mainLedger = Read-Ledger
$mainCache = Read-TairStringValue -Key $cacheKey

Write-Host "Running stage2 hotspot scenario..."
$hotspotRequests = $HotspotThreads * $HotspotLoops
Reset-SeckillData -InitialStock $Stock
$hotspotJMeter = Invoke-JMeterScenario -Name "seckill-stage2-hotspot" -ScenarioThreads $HotspotThreads -ScenarioLoops $HotspotLoops -ScenarioRamp $HotspotRamp -ScenarioUserIdStart $HotspotUserIdStart
Wait-SeckillLedger -MinimumConfirmed 0
$hotspotLedger = Read-Ledger
$hotspotCache = Read-TairStringValue -Key $cacheKey

$mainChecks = [ordered]@{
    jmeterFailures = ($mainJMeter.failures -eq 0)
    stock = ($mainLedger.stock -eq ($Stock - $mainExpectedSuccess))
    version = ($mainLedger.version -eq $mainExpectedSuccess)
    confirmed = ($mainLedger.confirmed -eq $mainExpectedSuccess)
    deductedCleared = ($mainLedger.deducted -eq 0)
    results = ($mainLedger.successResults -eq $mainExpectedSuccess -and $mainLedger.failedResults -eq 0)
    orders = ($mainLedger.seckillOrders -eq $mainExpectedSuccess)
    seckillMessagesDrained = ($mainLedger.openSeckillMessages -eq 0)
    cache = ($mainCache.value -eq $mainLedger.stock -and $mainCache.version -eq $mainLedger.version)
}

$hotspotChecks = [ordered]@{
    serverErrors = ($hotspotJMeter.serverErrors -le $MaxServerErrors)
    minExpectedFailures = ($hotspotJMeter.failures -ge $MinHotspotFailures)
    stockNeverNegative = ($hotspotLedger.stock -ge 0)
    confirmedWithinRequests = ($hotspotLedger.confirmed -le $hotspotRequests)
    stockMatchesConfirmed = ($hotspotLedger.stock -eq ($Stock - $hotspotLedger.confirmed))
    deductedCleared = ($hotspotLedger.deducted -eq 0)
    seckillMessagesDrained = ($hotspotLedger.openSeckillMessages -eq 0)
    cache = ($hotspotCache.value -eq $hotspotLedger.stock -and $hotspotCache.version -eq $hotspotLedger.version)
}

$passed = -not ($mainChecks.Values -contains $false) -and -not ($hotspotChecks.Values -contains $false)

$summary = [ordered]@{
    passed = $passed
    timestamp = $timestamp
    activityId = $ActivityId
    skuId = $SkuId
    main = [ordered]@{
        expectedSuccess = $mainExpectedSuccess
        jmeter = $mainJMeter
        database = $mainLedger
        cache = $mainCache
        checks = $mainChecks
    }
    hotspot = [ordered]@{
        requests = $hotspotRequests
        minExpectedFailures = $MinHotspotFailures
        maxServerErrors = $MaxServerErrors
        jmeter = $hotspotJMeter
        database = $hotspotLedger
        cache = $hotspotCache
        checks = $hotspotChecks
    }
}

$summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $summaryPath -Encoding UTF8
Write-Host "Stage2 verification summary: $summaryPath"
$summary | ConvertTo-Json -Depth 8

if (-not $passed) {
    exit 1
}
