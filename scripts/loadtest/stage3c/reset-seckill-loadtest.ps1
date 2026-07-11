param(
    [long]$ActivityId = 1,
    [long]$SkuId = 1001,
    [int]$Stock = 100000,
    [int]$BucketCount = 16,
    [string]$SkuName = "Headphones Black Flash",
    [decimal]$SeckillPrice = 499.00,

    [string]$MainObContainer = "mall-oceanbase-ce",
    [string]$MainObDatabase = "mall",
    [string]$ShardObContainer = "mall-oceanbase-ce-shard1",
    [string]$ShardObDatabase = "test",
    [string]$ObUser = "root@test",
    [int]$ObPort = 2881,

    [string]$MySqlContainer = "mall-mysql",
    [string]$MySqlDatabase = "mall",
    [string]$MySqlUser = "root",
    [string]$MySqlPassword = "root",

    [string]$TairContainer = "mall-tairstring",
    [int]$TairPort = 6379,
    [string]$RabbitContainer = "mall-rabbitmq",

    [switch]$ConfirmDestructive,
    [switch]$DryRun,
    [switch]$SkipRabbitMq,
    [switch]$SkipRedis,
    [switch]$SkipOrderDatabase,
    [switch]$SkipAppProcessCheck
)

$ErrorActionPreference = "Stop"

if ($ActivityId -le 0) {
    throw "ActivityId must be positive."
}
if ($SkuId -le 0) {
    throw "SkuId must be positive."
}
if ($Stock -lt 0) {
    throw "Stock must be zero or positive."
}
if ($BucketCount -le 0) {
    throw "BucketCount must be positive."
}
if (-not $DryRun -and -not $ConfirmDestructive) {
    throw "This reset deletes load-test data. Re-run with -ConfirmDestructive, or use -DryRun to preview."
}

$RabbitQueues = @(
    "mall.seckill.order.create.queue",
    "mall.seckill.order.create.dlq",
    "mall.seckill.order.result.queue",
    "mall.seckill.order.result.retry.delay.queue",
    "mall.seckill.order.result.dlq",
    "mall.order.close.queue",
    "mall.order.close.delay.queue",
    "mall.order.close.dlq"
)

function Quote-SqlString {
    param([string]$Value)
    return "'" + ($Value -replace "'", "''") + "'"
}

function Invoke-External {
    param(
        [string]$FilePath,
        [string[]]$Arguments,
        [switch]$AllowFailure
    )
    $display = "$FilePath $($Arguments -join ' ')"
    if ($DryRun) {
        Write-Host "DRY RUN: $display"
        return @()
    }

    $output = & $FilePath @Arguments 2>&1
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0 -and -not $AllowFailure) {
        throw "Command failed ($exitCode): $display`n$output"
    }
    if ($exitCode -ne 0) {
        Write-Warning "Command failed but was allowed ($exitCode): $display`n$output"
    }
    return $output
}

function Assert-ContainerRunning {
    param([string]$Name)
    if ($DryRun) {
        return
    }
    $result = Invoke-External -FilePath "docker" -Arguments @("inspect", "-f", "{{.State.Running}}", $Name)
    if (($result | Select-Object -First 1) -ne "true") {
        throw "Docker container is not running: $Name"
    }
}

function Assert-AppProcessesStopped {
    if ($SkipAppProcessCheck -or $DryRun) {
        return
    }
    $processes = @(Get-CimInstance Win32_Process |
        Where-Object {
            $_.ProcessName -like "java*" -and
            ($_.CommandLine -like "*mall-seckill*target*jar*" -or $_.CommandLine -like "*mall-order*target*jar*")
        } |
        Select-Object ProcessId, CommandLine)
    if ($processes.Count -gt 0) {
        $detail = $processes | Format-Table -AutoSize | Out-String
        throw "mall-order or mall-seckill is still running. Stop the apps before reset, or pass -SkipAppProcessCheck.`n$detail"
    }
}

function Invoke-ObSql {
    param(
        [string]$Container,
        [string]$Database,
        [string]$Sql
    )
    if ($DryRun) {
        Write-Host "DRY RUN: OceanBase SQL on $Container/$Database"
        Write-Host $Sql
        return
    }

    Write-Host "Running OceanBase SQL on $Container/$Database ..."
    $output = ($Sql.Trim() + [Environment]::NewLine) |
        & docker exec -i $Container obclient `
            -h 127.0.0.1 `
            -P $ObPort `
            -u $ObUser `
            -D $Database `
            --default-character-set=utf8mb4 2>&1
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        throw "OceanBase SQL failed on $Container/$Database ($exitCode):`n$output"
    }
    if ($output) {
        $output
    }
}

function Invoke-MySql {
    param([string]$Sql)
    if ($DryRun) {
        Write-Host "DRY RUN: MySQL SQL on $MySqlContainer/$MySqlDatabase"
        Write-Host $Sql
        return
    }

    Write-Host "Running MySQL SQL on $MySqlContainer/$MySqlDatabase ..."
    $output = ($Sql.Trim() + [Environment]::NewLine) |
        & docker exec -i -e "MYSQL_PWD=$MySqlPassword" $MySqlContainer mysql `
            --default-character-set=utf8mb4 `
            "-u$MySqlUser" `
            $MySqlDatabase 2>&1
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        throw "MySQL SQL failed on $MySqlContainer/$MySqlDatabase ($exitCode):`n$output"
    }
    if ($output) {
        $output
    }
}

function Remove-RedisKeysByPattern {
    param([string]$Pattern)
    if ($DryRun) {
        Write-Host "DRY RUN: scan and delete Redis keys matching $Pattern from ${TairContainer}:$TairPort"
        return
    }

    Write-Host "Scanning Redis keys matching $Pattern ..."
    $keys = @(& docker exec $TairContainer redis-cli -p $TairPort --scan --pattern $Pattern 2>&1)
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        throw "Redis scan failed ($exitCode):`n$keys"
    }

    $keys = @($keys | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Sort-Object -Unique)
    if ($keys.Count -eq 0) {
        Write-Host "No Redis keys matched $Pattern."
        return
    }

    $deleted = 0
    for ($i = 0; $i -lt $keys.Count; $i += 500) {
        $end = [Math]::Min($i + 499, $keys.Count - 1)
        $batch = @($keys[$i..$end])
        $result = & docker exec $TairContainer redis-cli -p $TairPort DEL @batch 2>&1
        $exitCode = $LASTEXITCODE
        if ($exitCode -ne 0) {
            throw "Redis DEL failed ($exitCode):`n$result"
        }
        $deleted += [int]($result | Select-Object -Last 1)
    }
    Write-Host "Deleted $deleted Redis keys matching $Pattern."
}

function Clear-RabbitQueues {
    foreach ($queue in $RabbitQueues) {
        Write-Host "Purging RabbitMQ queue $queue ..."
        Invoke-External -FilePath "docker" `
            -Arguments @("exec", $RabbitContainer, "rabbitmqctl", "purge_queue", $queue) `
            -AllowFailure | Out-Null
    }
}

function New-SurvivorBuckets {
    $survivors = New-Object System.Collections.Generic.List[string]
    for ($bucketNo = 1; $bucketNo -le $BucketCount; $bucketNo++) {
        if ((Get-BucketStock -BucketNo $bucketNo) -gt 0) {
            $survivors.Add([string]$bucketNo)
        }
    }
    return ($survivors -join ",")
}

function Get-BucketStock {
    param([int]$BucketNo)
    $base = [Math]::Floor($Stock / $BucketCount)
    $remainder = $Stock % $BucketCount
    if ($BucketNo -le $remainder) {
        return [int]($base + 1)
    }
    return [int]$base
}

function New-BucketInsertSql {
    param([int]$ShardModulo)
    $values = New-Object System.Collections.Generic.List[string]
    if ($ShardModulo -eq 0) {
        $values.Add("($ActivityId, $SkuId, 0, 'CENTER', 0, $Stock, 0, $Stock, 'ACTIVE', 0, NOW(), NOW())")
    }
    for ($bucketNo = 1; $bucketNo -le $BucketCount; $bucketNo++) {
        if (($bucketNo % 2) -ne $ShardModulo) {
            continue
        }
        $bucketStock = Get-BucketStock -BucketNo $bucketNo
        $status = "ACTIVE"
        if ($bucketStock -le 0) {
            $status = "EMPTY"
        }
        $values.Add("($ActivityId, $SkuId, $bucketNo, 'BUCKET', $bucketNo, $bucketStock, 0, 0, '$status', 0, NOW(), NOW())")
    }
    if ($values.Count -eq 0) {
        return ""
    }

    return @"
INSERT INTO seckill_stock_bucket
    (activity_id, sku_id, bucket_no, bucket_type, shard_key, saleable_quantity, occupy_quantity, setting_quantity, status, version, created_at, updated_at)
VALUES
    $($values -join ",`n    ");
"@
}

function New-SeckillShardSql {
    param(
        [switch]$PrimaryShard,
        [int]$ShardModulo
    )
    $skuNameSql = Quote-SqlString -Value $SkuName
    $priceSql = $SeckillPrice.ToString([System.Globalization.CultureInfo]::InvariantCulture)
    $survivorsSql = Quote-SqlString -Value (New-SurvivorBuckets)
    $bucketInsertSql = New-BucketInsertSql -ShardModulo $ShardModulo

    $singleTableSql = ""
    if ($PrimaryShard) {
        $singleTableSql = @"
DELETE FROM seckill_result_retry;
DELETE FROM seckill_result;

INSERT INTO seckill_activity (id, name, start_at, end_at)
VALUES ($ActivityId, 'Load Test Flash Sale', DATE_SUB(NOW(), INTERVAL 1 HOUR), DATE_ADD(NOW(), INTERVAL 1 DAY))
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    start_at = VALUES(start_at),
    end_at = VALUES(end_at);

INSERT INTO seckill_sku (activity_id, sku_id, sku_name, seckill_price, stock, version)
VALUES ($ActivityId, $SkuId, $skuNameSql, $priceSql, $Stock, 0)
ON DUPLICATE KEY UPDATE
    sku_name = VALUES(sku_name),
    seckill_price = VALUES(seckill_price),
    stock = VALUES(stock),
    version = 0;
"@
    }

    return @"
SET NAMES utf8mb4;

DELETE FROM seckill_stock_snapshot WHERE activity_id = $ActivityId AND sku_id = $SkuId;
DELETE FROM seckill_stock_change_log WHERE activity_id = $ActivityId AND sku_id = $SkuId;
DELETE FROM mq_message;
DELETE FROM seckill_stock_bucket WHERE activity_id = $ActivityId AND sku_id = $SkuId;
DELETE FROM seckill_bucket_config WHERE activity_id = $ActivityId AND sku_id = $SkuId;
$singleTableSql
INSERT INTO seckill_bucket_config
    (activity_id, sku_id, bucket_count, route_mode, status, strategy_version, survivor_buckets, created_at, updated_at)
VALUES
    ($ActivityId, $SkuId, $BucketCount, 'RANDOM_ACTIVE', 'ENABLED', 1, $survivorsSql, NOW(), NOW());

$bucketInsertSql

SELECT 'seckill_stock_bucket' AS table_name, bucket_type, status, COUNT(*) AS count, COALESCE(SUM(saleable_quantity), 0) AS saleable
FROM seckill_stock_bucket
WHERE activity_id = $ActivityId AND sku_id = $SkuId
GROUP BY bucket_type, status;
SELECT 'seckill_bucket_config' AS table_name, COUNT(*) AS count FROM seckill_bucket_config WHERE activity_id = $ActivityId AND sku_id = $SkuId;
"@
}

function New-OrderDatabaseSql {
    return @"
SET NAMES utf8mb4;

CREATE TEMPORARY TABLE tmp_reset_seckill_order_sn (
    order_sn VARCHAR(64) PRIMARY KEY
);

CREATE TEMPORARY TABLE tmp_reset_seckill_reservation (
    reservation_id VARCHAR(64) PRIMARY KEY
);

INSERT IGNORE INTO tmp_reset_seckill_order_sn (order_sn)
SELECT order_sn FROM order_info WHERE source = 'SECKILL';

INSERT IGNORE INTO tmp_reset_seckill_reservation (reservation_id)
SELECT source_id FROM order_info WHERE source = 'SECKILL' AND source_id IS NOT NULL AND source_id <> '';

INSERT IGNORE INTO tmp_reset_seckill_reservation (reservation_id)
SELECT reservation_id FROM seckill_order WHERE reservation_id IS NOT NULL AND reservation_id <> '';

DELETE m FROM mq_message m
LEFT JOIN tmp_reset_seckill_order_sn o ON m.business_key = o.order_sn
LEFT JOIN tmp_reset_seckill_reservation r ON m.business_key = r.reservation_id
WHERE m.routing_key IN (
    'seckill.order.create',
    'seckill.order.create.dlq',
    'seckill.order.result',
    'seckill.order.result.retry.delay',
    'seckill.order.result.dlq'
)
OR o.order_sn IS NOT NULL
OR r.reservation_id IS NOT NULL;

DELETE c FROM consume_record c
JOIN tmp_reset_seckill_reservation r ON c.message_id = r.reservation_id;

DELETE i FROM order_item i
JOIN tmp_reset_seckill_order_sn o ON i.order_sn = o.order_sn;

DELETE FROM seckill_order;
DELETE o FROM order_info o
JOIN tmp_reset_seckill_order_sn s ON o.order_sn = s.order_sn;

SELECT 'seckill_order' AS table_name, COUNT(*) AS count FROM seckill_order;
SELECT 'seckill_order_info' AS table_name, COUNT(*) AS count FROM order_info WHERE source = 'SECKILL';
SELECT 'seckill_mq_message' AS table_name, COUNT(*) AS count
FROM mq_message
WHERE routing_key LIKE 'seckill.%';
"@
}

function Remove-RedisKeysByPatternSafe {
    param([string]$Pattern)

    if ($DryRun) {
        Write-Host "DRY RUN: scan delete Redis keys matching $Pattern in ${TairContainer}:$TairPort"
        return
    }

    Write-Host "Scanning Redis keys matching $Pattern ..."
    $keys = @(& docker exec $TairContainer redis-cli -p $TairPort --scan --pattern $Pattern 2>&1)
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        throw "Redis scan failed ($exitCode):`n$keys"
    }

    $keys = @($keys | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Sort-Object -Unique)
    if ($keys.Count -eq 0) {
        Write-Host "No Redis keys matched $Pattern."
        return
    }

    $deleted = 0
    for ($i = 0; $i -lt $keys.Count; $i += 200) {
        $end = [Math]::Min($i + 199, $keys.Count - 1)
        $batch = @($keys[$i..$end])
        $payloadBuilder = New-Object System.Text.StringBuilder
        foreach ($key in $batch) {
            [void]$payloadBuilder.Append("*2`r`n")
            [void]$payloadBuilder.Append("`$3`r`nDEL`r`n")
            [void]$payloadBuilder.Append("`$$($key.Length)`r`n")
            [void]$payloadBuilder.Append($key)
            [void]$payloadBuilder.Append("`r`n")
        }

        $tempFile = Join-Path $env:TEMP ("redis-del-" + [guid]::NewGuid().ToString() + ".resp")
        try {
            [System.IO.File]::WriteAllText($tempFile, $payloadBuilder.ToString(), [System.Text.UTF8Encoding]::new($false))
            $cmdLine = 'type "' + $tempFile + '" | docker exec -i ' + $TairContainer + ' redis-cli -p ' + $TairPort + ' --pipe'
            $result = & cmd /c $cmdLine 2>&1
        } finally {
            Remove-Item -LiteralPath $tempFile -Force -ErrorAction SilentlyContinue
        }
        $exitCode = $LASTEXITCODE
        if ($exitCode -ne 0) {
            throw "Redis DEL failed ($exitCode):`n$result"
        }
        if (-not (($result | Out-String) -match "errors:\s*0")) {
            throw "Redis DEL reported pipe errors:`n$result"
        }
        $deleted += $batch.Count
    }

    Write-Host "Deleted $deleted Redis keys matching $Pattern."
}

Write-Host "Stage3c seckill reset parameters:"
Write-Host "  ActivityId=$ActivityId SkuId=$SkuId Stock=$Stock BucketCount=$BucketCount"
Write-Host "  Safe mode: Redis deletes only *seckill* keys; RabbitMQ purges only known load-test queues."

Assert-AppProcessesStopped

Assert-ContainerRunning -Name $MainObContainer
Assert-ContainerRunning -Name $ShardObContainer
if (-not $SkipOrderDatabase) {
    Assert-ContainerRunning -Name $MySqlContainer
}
if (-not $SkipRedis) {
    Assert-ContainerRunning -Name $TairContainer
}
if (-not $SkipRabbitMq) {
    Assert-ContainerRunning -Name $RabbitContainer
}

if (-not $SkipRabbitMq) {
    Clear-RabbitQueues
}
if (-not $SkipRedis) {
    Remove-RedisKeysByPatternSafe -Pattern "*seckill*"
}

Invoke-ObSql -Container $MainObContainer -Database $MainObDatabase -Sql (New-SeckillShardSql -PrimaryShard -ShardModulo 0)
Invoke-ObSql -Container $ShardObContainer -Database $ShardObDatabase -Sql (New-SeckillShardSql -ShardModulo 1)

if (-not $SkipOrderDatabase) {
    Invoke-MySql -Sql (New-OrderDatabaseSql)
}

Write-Host "Stage3c seckill reset completed."
