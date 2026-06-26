param(
    [string]$GatewayBaseUrl = "http://110.20.1.12:8385",
    [string]$ConnSysId = "HOH4-N9PX-73Y6-B393",
    [string]$AdmSecCd = "46870",
    [string]$GpkiId = "null",
    [string]$Pnu = "4687025625111190010",
    [string]$LayerCd = "LSMD_CONT_LDREG",
    [string]$OutDir = ".\api-tests\out",
    [switch]$DownloadLayerFiles,
    [switch]$IncludeLargeTextDownloads
)

$ErrorActionPreference = "Stop"

function Invoke-KrasGateway {
    param(
        [string]$Name,
        [hashtable]$Body,
        [string]$OutFile
    )

    $uri = "$GatewayBaseUrl/conn/estateGateway"
    Write-Host "[$Name] POST $uri"
    Write-Host ("  body: " + (($Body.GetEnumerator() | Sort-Object Name | ForEach-Object { "$($_.Name)=$($_.Value)" }) -join "&"))

    if ($OutFile) {
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $OutFile) | Out-Null
        Invoke-WebRequest -Uri $uri -Method Post -Body $Body -UseBasicParsing -TimeoutSec 120 -OutFile $OutFile
        $saved = Get-Item -LiteralPath $OutFile
        Write-Host "  bytes: $($saved.Length)"
        Write-Host "  saved: $OutFile"
    } else {
        $response = Invoke-WebRequest -Uri $uri -Method Post -Body $Body -UseBasicParsing -TimeoutSec 60
        Write-Host "  status: $($response.StatusCode), bytes: $($response.RawContentLength)"
        $preview = $response.Content
        if ($preview.Length -gt 500) {
            $preview = $preview.Substring(0, 500)
        }
        Write-Host "  preview: $preview"
    }
}

$common = @{
    conn_sys_id = $ConnSysId
    adm_sec_cd = $AdmSecCd
    gpki_id = $GpkiId
}

Invoke-KrasGateway -Name "KRAS000037 layer list" -Body ($common + @{
    conn_svc_id = "KRAS000037"
})

Invoke-KrasGateway -Name "KRAS000011 land price existence check" -Body ($common + @{
    conn_svc_id = "KRAS000011"
    pnu = $Pnu
})

if ($DownloadLayerFiles) {
    $fileTypes = @(
        @{ Type = "2"; Ext = "shp" },
        @{ Type = "3"; Ext = "dbf" },
        @{ Type = "4"; Ext = "shx" }
    )

    foreach ($fileType in $fileTypes) {
        Invoke-KrasGateway -Name "KRAS000038 $LayerCd .$($fileType.Ext)" -Body ($common + @{
            conn_svc_id = "KRAS000038"
            layer_cd = $LayerCd
            file_type = $fileType.Type
        }) -OutFile (Join-Path $OutDir "$($LayerCd.ToLower()).$($fileType.Ext)")
    }
}

if ($IncludeLargeTextDownloads) {
    Invoke-KrasGateway -Name "KRAS000039 official land price TXT" -Body ($common + @{
        conn_svc_id = "KRAS000039"
    }) -OutFile (Join-Path $OutDir "anvm_jiga.txt")

    Invoke-KrasGateway -Name "KRAS000040 land ledger TXT" -Body ($common + @{
        conn_svc_id = "KRAS000040"
    }) -OutFile (Join-Path $OutDir "land_frst_ledg.txt")
}
