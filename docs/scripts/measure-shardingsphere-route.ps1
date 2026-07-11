param(
    [Parameter(Mandatory = $true)]
    [string]$LogPath,

    [string]$SummaryPath = ""
)

if (-not (Test-Path -LiteralPath $LogPath)) {
    throw "Log file not found: $LogPath"
}

$targetTables = @(
    "seckill_stock_bucket",
    "seckill_stock_snapshot",
    "seckill_stock_change_log",
    "mq_message",
    "seckill_result_retry"
)

function New-RouteBlock {
    param([string]$LogicSql)
    [ordered]@{
        logicSql = $LogicSql
        operation = "UNKNOWN"
        table = "UNKNOWN"
        dataSources = New-Object System.Collections.Generic.HashSet[string]
        actualCount = 0
    }
}

function Complete-RouteBlock {
    param(
        [hashtable]$Block,
        [System.Collections.Generic.List[object]]$Blocks
    )
    if ($null -eq $Block -or $Block.actualCount -le 0) {
        return
    }
    $sql = $Block.logicSql
    if ($sql -match '(?i)^\s*(UPDATE|INSERT|SELECT|DELETE)\b') {
        $Block.operation = $Matches[1].ToUpperInvariant()
    }
    foreach ($table in $targetTables) {
        if ($sql -match "(?i)\b$table\b") {
            $Block.table = $table
            break
        }
    }
    if ($Block.table -ne "UNKNOWN") {
        $Blocks.Add([pscustomobject]$Block)
    }
}

$blocks = New-Object System.Collections.Generic.List[object]
$current = $null

foreach ($line in Get-Content -LiteralPath $LogPath -Encoding UTF8) {
    if ($line -match 'Logic SQL:\s*(.+)$') {
        Complete-RouteBlock -Block $current -Blocks $blocks
        $current = New-RouteBlock -LogicSql $Matches[1]
        continue
    }
    if ($line -match 'Actual SQL:\s*([A-Za-z0-9_]+)\s*:::\s*(.+)$') {
        if ($null -eq $current) {
            $current = New-RouteBlock -LogicSql $Matches[2]
        }
        [void]$current.dataSources.Add($Matches[1])
        $current.actualCount++
    }
}
Complete-RouteBlock -Block $current -Blocks $blocks

$summary = [ordered]@{
    logPath = (Resolve-Path -LiteralPath $LogPath).Path
    statementCount = $blocks.Count
    singleShardStatementCount = 0
    multiShardStatementCount = 0
    singleShardUpdateCount = 0
    multiShardUpdateCount = 0
    broadcastUpdateCount = 0
    byTable = [ordered]@{}
}

foreach ($table in $targetTables) {
    $summary.byTable[$table] = [ordered]@{
        statementCount = 0
        singleShardStatementCount = 0
        multiShardStatementCount = 0
        updateCount = 0
        singleShardUpdateCount = 0
        multiShardUpdateCount = 0
        dataSources = @{}
    }
}

foreach ($block in $blocks) {
    $isSingle = $block.dataSources.Count -eq 1
    if ($isSingle) {
        $summary.singleShardStatementCount++
    } else {
        $summary.multiShardStatementCount++
    }

    $tableSummary = $summary.byTable[$block.table]
    $tableSummary.statementCount++
    if ($isSingle) {
        $tableSummary.singleShardStatementCount++
    } else {
        $tableSummary.multiShardStatementCount++
    }
    foreach ($dataSource in $block.dataSources) {
        if (-not $tableSummary.dataSources.Contains($dataSource)) {
            $tableSummary.dataSources[$dataSource] = 0
        }
        $tableSummary.dataSources[$dataSource]++
    }

    if ($block.operation -eq "UPDATE") {
        $summary.byTable[$block.table].updateCount++
        if ($isSingle) {
            $summary.singleShardUpdateCount++
            $tableSummary.singleShardUpdateCount++
        } else {
            $summary.multiShardUpdateCount++
            $summary.broadcastUpdateCount++
            $tableSummary.multiShardUpdateCount++
        }
    }
}

$json = $summary | ConvertTo-Json -Depth 8
if ([string]::IsNullOrWhiteSpace($SummaryPath)) {
    $json
} else {
    $directory = Split-Path -Parent $SummaryPath
    if (-not [string]::IsNullOrWhiteSpace($directory)) {
        New-Item -ItemType Directory -Force -Path $directory | Out-Null
    }
    $json | Set-Content -LiteralPath $SummaryPath -Encoding UTF8
    Write-Host "Route summary written to $SummaryPath"
}
