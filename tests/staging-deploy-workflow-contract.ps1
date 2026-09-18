[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$workflowPath = Join-Path $repo '.github/workflows/staging-deploy.yml'
if (-not (Test-Path -LiteralPath $workflowPath -PathType Leaf)) { throw 'Dedicated staging workflow is missing.' }
$workflow = (Get-Content -LiteralPath $workflowPath -Raw) -replace "`r`n", "`n"

function Require([string]$Text, [string]$Message) {
    if (-not $workflow.Contains($Text)) { throw "Missing staging workflow contract: $Message" }
}
function Reject([string]$Pattern, [string]$Message) {
    if ($workflow -match $Pattern) { throw "Rejected staging workflow contract: $Message" }
}

Require "branches:`n      - develop" 'push must map only develop to staging.'
Require "if: github.event_name == 'push' && github.ref == 'refs/heads/develop' && vars.APP_CLOUD_DEPLOYMENT_ENABLED == 'true'" 'cloud deployment must be explicitly gated.'
Require 'environment:' 'staging must use the protected GitHub environment.'
Require 'name: staging' 'staging environment name.'
Require "permissions:`n      contents: read`n      id-token: write" 'OIDC permission must be scoped to the staging job.'
Require 'uses: aws-actions/configure-aws-credentials@cabfdba3510de1431bac9dba27511d97497fc100' 'pinned AWS OIDC configuration.'
Require 'role-to-assume: ${{ vars.APP_CLOUD_DEPLOYMENT_ROLE_ARN }}' 'reviewed launcher role variable.'
Require 'uses: actions/checkout@v4' 'reviewed source checkout.'
Require 'fetch-depth: 0' 'full history required for immutable source packaging.'
Require './scripts/offline-release-handoff.ps1' 'reviewed source/release manifest packaging.'
Require './scripts/start-deploy.ps1' 'reviewed APP launcher invocation.'
Require '-Bucket $env:APP_ARTIFACT_BUCKET' 'exact artifact bucket input.'
Require '-SourcePrefix $env:APP_SOURCE_PREFIX' 'exact artifact source prefix input.'
Require '-ProjectName $env:APP_PROJECT_NAME' 'exact CodeBuild project input.'
Require 'APP_ARTIFACT_BUCKET: oficina-phase3-artifacts-16225b7358' 'reviewed APP artifact bucket.'
Require 'APP_SOURCE_PREFIX: releases/app/staging' 'reviewed APP staging prefix.'
Require 'APP_PROJECT_NAME: oficina-phase3-oficina-app-staging-deploy' 'reviewed APP staging project.'
Require 'APP_TERRAFORM_BACKEND_KEY: app/staging.tfstate' 'canonical APP staging Terraform state key.'
Require 'APP_TERRAFORM_BACKEND_LOCK_KEY: app/staging.tfstate.tflock' 'canonical APP staging Terraform lock key.'
Require 'APP_TERRAFORM_EXECUTOR_TFVARS_PATH: /tmp/oficina/app_staging.tfvars.json' 'canonical APP staging executor tfvars path.'
Require 'APP_RELEASE_INPUT_PATH' 'reviewed release input variable.'
Require 'APP_PLATFORM_INPUTS_PATH' 'reviewed platform input variable.'
Require 'APP_CLOUD_WINDOW_EVIDENCE_PATH' 'reviewed cloud-window evidence variable.'
foreach ($name in @('APP_TERRAFORM_VARIABLES_PATH', 'APP_TERRAFORM_VARIABLES_SHA256', 'APP_DEPLOYER_IMAGE_DIGEST')) {
    Require ($name + ': ${{ vars.' + $name + ' }}') "reviewed $name configuration."
    Require ($name + ' = $env:' + $name) "missing $name must fail required-input validation."
}
Require '$env:APP_CLOUD_WINDOW_EVIDENCE_PATH, $env:APP_TERRAFORM_VARIABLES_PATH)' 'reviewed tfvars file must exist before credentials.'
Require 'name: Validate immutable deployment bindings without AWS' 'offline launcher preflight.'
Require '-DryRun' 'preflight must not perform AWS mutations.'
foreach ($binding in @('-TerraformVariablesFile $env:APP_TERRAFORM_VARIABLES_PATH', '-ExpectedTerraformVariablesSha256 $env:APP_TERRAFORM_VARIABLES_SHA256', '-DeployerImageDigest $env:APP_DEPLOYER_IMAGE_DIGEST')) {
    if ([regex]::Matches($workflow, [regex]::Escape($binding)).Count -ne 2) { throw "Both preflight and launch must pass the same reviewed binding: $binding" }
}
if ($workflow.IndexOf('name: Validate immutable deployment bindings without AWS') -gt $workflow.IndexOf('name: Configure AWS OIDC')) { throw 'Immutable input validation must happen before requesting AWS credentials.' }
Reject 'APP_TERRAFORM_VARIABLES_SHA256\s*=\s*\(?Get-FileHash' 'tfvars review digest must be independently supplied, not minted by the workflow.'
Require 'cancel-in-progress: false' 'deployment runs must not be cancelled.'

Reject 'pull_request|pull_request_target|workflow_dispatch|workflow_dispatch:|schedule:|tags:' 'PR, manual, scheduled and tag activation.'
Reject 'refs/heads/main|refs/heads/master|production' 'production activation or a legacy branch escape.'
Reject 'AWS_ACCESS_KEY_ID|AWS_SECRET_ACCESS_KEY|secrets\.' 'long-lived credentials or secret values in source.'
if ($workflow -match '(?m)^permissions:\n(?:  [^\r\n]*\n)*  id-token:\s+write') { throw 'Global permissions must not grant id-token: write.' }

Write-Output 'PASS: dedicated staging deploy workflow is develop-only, explicitly gated, OIDC-scoped, source-pinned and production-free.'
