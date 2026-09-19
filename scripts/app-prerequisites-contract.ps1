Set-StrictMode -Version Latest
. "$PSScriptRoot/staging-prerequisites-contract.ps1"
function Read-AppPrerequisites($Platform,$Release){
    if($Release.environment -cne 'staging' -or $Release.mode -cne 'FirstWriter'){throw 'APP_PREREQUISITES_STAGING_ONLY'}
    foreach($field in @('StagingPrerequisitesJson','StagingPrerequisitesSha256','PlatformPrerequisitesReceiptJson','PlatformPrerequisitesReceiptSha256','PlatformPrerequisitesReceiptBucket','PlatformPrerequisitesReceiptKey','PlatformPrerequisitesReceiptVersionId')){if($Platform.$field -isnot [string] -or [string]::IsNullOrWhiteSpace($Platform.$field)){throw 'APP_PREREQUISITES_REVIEW_REQUIRED'}}
    $bundle=Read-StagingPrerequisites $Platform.StagingPrerequisitesJson $Platform.StagingPrerequisitesSha256 $Release.sourceCommit
    $account=[regex]::Match($Release.image,'^([0-9]{12})\.dkr\.ecr\.us-east-1\.amazonaws\.com/').Groups[1].Value
    if($bundle.targetGroupArn -cnotmatch "^arn:aws:elasticloadbalancing:us-east-1:${account}:" -or $bundle.migrationNetworkPolicySha256 -isnot [string] -or $bundle.migrationNetworkPolicySha256 -cne $Platform.MigrationNetworkPolicySha256 -or $bundle.runtimePublicConfigMapSha256 -isnot [string] -or $bundle.runtimePublicConfigMapSha256 -cne $Release.runtimePublicConfigMapSha256 -or
       $Platform.PlatformPrerequisitesReceiptSha256 -cnotmatch '^[a-f0-9]{64}$' -or (Get-PrerequisiteHash $Platform.PlatformPrerequisitesReceiptJson) -cne $Platform.PlatformPrerequisitesReceiptSha256 -or
       $Platform.PlatformPrerequisitesReceiptBucket -cnotmatch '^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$' -or $Platform.PlatformPrerequisitesReceiptKey -cne "foundation-addons/manifests/staging/$($Release.sourceCommit)/readback.json" -or $Platform.PlatformPrerequisitesReceiptVersionId -ceq 'null'){throw 'APP_PREREQUISITES_RECEIPT_BINDING_MISMATCH'}
    $receipt=ConvertFrom-Json -InputObject $Platform.PlatformPrerequisitesReceiptJson -NoEnumerate
    Assert-PrerequisiteReceipt $receipt $bundle $Platform.StagingPrerequisitesSha256
    $bundle|Add-Member -NotePropertyName reviewedReadback -NotePropertyValue $receipt.objects
    return $bundle
}
