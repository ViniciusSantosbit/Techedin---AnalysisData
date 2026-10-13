param([ValidateRange(1,100)][int]$MaxJobs = 20)
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
python ingestion/scrapers/job_scraper.py --export-json frontend/data/jobs.json --max-jobs $MaxJobs
if ($LASTEXITCODE -ne 0) { throw 'A coleta falhou. O último snapshot válido foi preservado.' }
Write-Host 'Dados reais atualizados. Recarregue http://localhost:8081.'
