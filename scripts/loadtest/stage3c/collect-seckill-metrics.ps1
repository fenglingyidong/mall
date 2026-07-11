param(
    [ValidateSet("Snapshot", "Watch")]
    [string]$Mode = "Snapshot",

    [string]$BaseUrl = "http://localhost:8105",

    [Parameter(Mandatory = $true)]
    [string]$OutputDir,

    [string]$Label = "before",

    [string[]]$MetricNames = @(
        "seckill.submit.total",
        "seckill.submit.stock-cache.sold-out",
        "seckill.submit.record.snapshot.insert",
        "seckill.submit.record.bucket.route",
        "seckill.submit.record.bucket.db.deduct",
        "seckill.submit.record.bucket.change-log.insert",
        "seckill.submit.record.stock.update"
    ),

    [int]$IntervalSeconds = 2,
    [int]$DurationSeconds = 0,
    [int]$TimeoutSeconds = 3
)

$ErrorActionPreference = "Stop"

function Ensure-Directory {
    param([string]$Path)

    if ([string]::IsNullOrWhiteSpace($Path)) {
        throw "Path must not be empty."
    }
    New-Item -ItemType Directory -Force -Path $Path | Out-Null
}

function Get-MetricFileName {
    param([string]$MetricName)

    return ($MetricName -replace "[^A-Za-z0-9_.-]", "_") + ".json"
}

function Write-JsonFile {
    param(
        [string]$Path,
        [object]$Value,
        [int]$Depth = 10
    )

    $directory = Split-Path -Parent $Path
    if (-not [string]::IsNullOrWhiteSpace($directory)) {
        Ensure-Directory -Path $directory
    }
    $Value | ConvertTo-Json -Depth $Depth | Set-Content -LiteralPath $Path -Encoding UTF8
}

function Add-JsonLine {
    param(
        [string]$Path,
        [object]$Value,
        [int]$Depth = 20
    )

    $directory = Split-Path -Parent $Path
    if (-not [string]::IsNullOrWhiteSpace($directory)) {
        Ensure-Directory -Path $directory
    }
    ($Value | ConvertTo-Json -Depth $Depth -Compress) | Add-Content -LiteralPath $Path -Encoding UTF8
}

function Read-JsonFile {
    param([string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        return $null
    }
    return Get-Content -Raw -Encoding UTF8 -LiteralPath $Path | ConvertFrom-Json
}

function Invoke-MetricRequest {
    param([string]$MetricName)

    $encodedMetricName = [System.Uri]::EscapeDataString($MetricName)
    $uri = "$($BaseUrl.TrimEnd('/'))/actuator/metrics/$encodedMetricName"
    try {
        $response = Invoke-RestMethod -Method Get -Uri $uri -TimeoutSec $TimeoutSeconds
        return [ordered]@{
            ok = $true
            metric = $MetricName
            uri = $uri
            response = $response
            error = $null
        }
    } catch {
        return [ordered]@{
            ok = $false
            metric = $MetricName
            uri = $uri
            response = $null
            error = $_.Exception.Message
        }
    }
}

function Convert-MeasurementsToMap {
    param([object]$MetricResponse)

    $map = [ordered]@{}
    if ($null -eq $MetricResponse -or $null -eq $MetricResponse.measurements) {
        return $map
    }
    foreach ($measurement in $MetricResponse.measurements) {
        $map[$measurement.statistic] = [double]$measurement.value
    }
    return $map
}

function Convert-MetricResultToSummaryItem {
    param([hashtable]$MetricResult)

    $availableTags = @()
    if ($null -ne $MetricResult.response -and $null -ne $MetricResult.response.availableTags) {
        $availableTags = @($MetricResult.response.availableTags)
    }

    return [ordered]@{
        metric = $MetricResult.metric
        ok = $MetricResult.ok
        uri = $MetricResult.uri
        error = $MetricResult.error
        measurements = Convert-MeasurementsToMap -MetricResponse $MetricResult.response
        availableTags = $availableTags
    }
}

function Find-SummaryMetric {
    param(
        [object[]]$Metrics,
        [string]$MetricName
    )

    foreach ($metric in $Metrics) {
        if ($metric.metric -eq $MetricName) {
            return $metric
        }
    }
    return $null
}

function Get-MeasurementValue {
    param(
        [object]$SummaryMetric,
        [string]$Statistic
    )

    if ($null -eq $SummaryMetric -or $null -eq $SummaryMetric.measurements) {
        return $null
    }
    $property = $SummaryMetric.measurements.PSObject.Properties[$Statistic]
    if ($null -eq $property) {
        return $null
    }
    return [double]$property.Value
}

function Invoke-Snapshot {
    $snapshotDir = Join-Path $OutputDir $Label
    Ensure-Directory -Path $snapshotDir

    $items = New-Object System.Collections.Generic.List[object]
    foreach ($metricName in $MetricNames) {
        $result = Invoke-MetricRequest -MetricName $metricName
        $filePath = Join-Path $snapshotDir (Get-MetricFileName -MetricName $metricName)
        Write-JsonFile -Path $filePath -Value $result -Depth 20
        $items.Add([pscustomobject](Convert-MetricResultToSummaryItem -MetricResult $result))
    }

    $summary = [ordered]@{
        mode = "Snapshot"
        label = $Label
        baseUrl = $BaseUrl
        capturedAt = (Get-Date).ToString("o")
        metrics = $items
    }
    Write-JsonFile -Path (Join-Path $snapshotDir "summary.json") -Value $summary -Depth 20
    return $summary
}

function Invoke-Watch {
    $duringDir = Join-Path $OutputDir "during"
    Ensure-Directory -Path $duringDir
    $metricsPath = Join-Path $duringDir "metrics.jsonl"
    $errorsPath = Join-Path $duringDir "errors.jsonl"
    $startedAt = Get-Date
    $deadline = $null
    if ($DurationSeconds -gt 0) {
        $deadline = $startedAt.AddSeconds($DurationSeconds)
    }

    do {
        $sampledAt = Get-Date
        foreach ($metricName in $MetricNames) {
            $result = Invoke-MetricRequest -MetricName $metricName
            $availableTags = @()
            if ($null -ne $result.response -and $null -ne $result.response.availableTags) {
                $availableTags = @($result.response.availableTags)
            }

            $line = [ordered]@{
                sampledAt = $sampledAt.ToString("o")
                elapsedSeconds = [math]::Round(($sampledAt - $startedAt).TotalSeconds, 3)
                metric = $metricName
                ok = $result.ok
                uri = $result.uri
                measurements = Convert-MeasurementsToMap -MetricResponse $result.response
                availableTags = $availableTags
                error = $result.error
            }
            Add-JsonLine -Path $metricsPath -Value $line
            if (-not $result.ok) {
                Add-JsonLine -Path $errorsPath -Value $line
            }
        }

        if ($null -ne $deadline -and (Get-Date) -ge $deadline) {
            break
        }
        Start-Sleep -Seconds $IntervalSeconds
    } while ($true)
}

function Read-DuringPeaks {
    $metricsPath = Join-Path (Join-Path $OutputDir "during") "metrics.jsonl"
    $peaks = [ordered]@{}
    if (-not (Test-Path -LiteralPath $metricsPath)) {
        return $peaks
    }

    foreach ($line in Get-Content -Encoding UTF8 -LiteralPath $metricsPath) {
        if ([string]::IsNullOrWhiteSpace($line)) {
            continue
        }
        $sample = $line | ConvertFrom-Json
        if (-not $sample.ok) {
            continue
        }
        $maxProperty = $sample.measurements.PSObject.Properties["MAX"]
        if ($null -eq $maxProperty) {
            continue
        }

        $currentMax = [double]$maxProperty.Value
        if (-not $peaks.Contains($sample.metric) -or $currentMax -gt $peaks[$sample.metric].max) {
            $peaks[$sample.metric] = [ordered]@{
                metric = $sample.metric
                sampledAt = $sample.sampledAt
                max = $currentMax
                maxMs = [math]::Round($currentMax * 1000.0, 3)
            }
        }
    }
    return $peaks
}

function Update-RunSummary {
    $before = Read-JsonFile -Path (Join-Path (Join-Path $OutputDir "before") "summary.json")
    $after = Read-JsonFile -Path (Join-Path (Join-Path $OutputDir "after") "summary.json")
    if ($null -eq $before -or $null -eq $after) {
        return
    }

    $duringPeaks = Read-DuringPeaks
    $items = New-Object System.Collections.Generic.List[object]
    foreach ($metricName in $MetricNames) {
        $beforeMetric = Find-SummaryMetric -Metrics $before.metrics -MetricName $metricName
        $afterMetric = Find-SummaryMetric -Metrics $after.metrics -MetricName $metricName
        $beforeCount = Get-MeasurementValue -SummaryMetric $beforeMetric -Statistic "COUNT"
        $afterCount = Get-MeasurementValue -SummaryMetric $afterMetric -Statistic "COUNT"
        $beforeTotal = Get-MeasurementValue -SummaryMetric $beforeMetric -Statistic "TOTAL_TIME"
        $afterTotal = Get-MeasurementValue -SummaryMetric $afterMetric -Statistic "TOTAL_TIME"
        $afterMax = Get-MeasurementValue -SummaryMetric $afterMetric -Statistic "MAX"

        $countDelta = if ($null -ne $beforeCount -and $null -ne $afterCount) { $afterCount - $beforeCount } else { $null }
        $totalDelta = if ($null -ne $beforeTotal -and $null -ne $afterTotal) { $afterTotal - $beforeTotal } else { $null }
        $avgMs = if ($null -ne $countDelta -and $countDelta -gt 0 -and $null -ne $totalDelta) {
            [math]::Round(($totalDelta / $countDelta) * 1000.0, 3)
        } else {
            $null
        }

        $peak = $null
        if ($duringPeaks.Contains($metricName)) {
            $peak = $duringPeaks[$metricName]
        }

        $items.Add([pscustomobject][ordered]@{
            metric = $metricName
            okBefore = if ($null -eq $beforeMetric) { $false } else { [bool]$beforeMetric.ok }
            okAfter = if ($null -eq $afterMetric) { $false } else { [bool]$afterMetric.ok }
            beforeCount = $beforeCount
            afterCount = $afterCount
            countDelta = $countDelta
            beforeTotalTime = $beforeTotal
            afterTotalTime = $afterTotal
            totalTimeDelta = $totalDelta
            avgMs = $avgMs
            afterMaxMs = if ($null -eq $afterMax) { $null } else { [math]::Round($afterMax * 1000.0, 3) }
            duringPeak = $peak
        })
    }

    $summary = [ordered]@{
        baseUrl = $BaseUrl
        generatedAt = (Get-Date).ToString("o")
        beforeCapturedAt = $before.capturedAt
        afterCapturedAt = $after.capturedAt
        metrics = $items
    }
    Write-JsonFile -Path (Join-Path $OutputDir "summary.json") -Value $summary -Depth 30
}

Ensure-Directory -Path $OutputDir

if ($Mode -eq "Snapshot") {
    Invoke-Snapshot | Out-Null
    if ($Label -eq "after") {
        Update-RunSummary
    }
    exit 0
}

if ($Mode -eq "Watch") {
    Invoke-Watch
    exit 0
}

throw "Unsupported mode: $Mode"
