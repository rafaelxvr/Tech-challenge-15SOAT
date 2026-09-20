function Add-StagingPrerequisitesFixture([System.Collections.IDictionary]$Release,[string]$PlatformPath){
    $platform=Get-Content $PlatformPath -Raw|ConvertFrom-Json -AsHashtable
    $public=Get-Content (Join-Path (Split-Path -Parent $PlatformPath) 'public.json') -Raw|ConvertFrom-Json -AsHashtable
    $objects=@($public,(New-MigrationServiceAccountFixture))
    foreach($entry in @(@{kind='Service';name='oficina-app';api='v1';spec=@{selector=@{'app.kubernetes.io/name'='oficina-app'};ports=@(@{port=8080;targetPort=8080;protocol='TCP'});type='ClusterIP'}},@{kind='SecretProviderClass';name='oficina-runtime-secrets';api='secrets-store.csi.x-k8s.io/v1';spec=@{provider='aws';parameters=@{region='us-east-1';objects='SYNTHETIC REFERENCES ONLY'}}},@{kind='NetworkPolicy';name='default-deny-ingress-egress';api='networking.k8s.io/v1';spec=@{podSelector=@{};policyTypes=@('Ingress','Egress')}},@{kind='NetworkPolicy';name='oficina-app-allow-required-paths';api='networking.k8s.io/v1';spec=@{podSelector=@{matchLabels=@{'app.kubernetes.io/name'='oficina-app'}};policyTypes=@('Ingress','Egress')}},@{kind='NetworkPolicy';name='oficina-migration-allow-required-paths';api='networking.k8s.io/v1';spec=@{podSelector=@{matchLabels=@{'app.kubernetes.io/name'='oficina-migration'}};policyTypes=@('Egress')}})){
        $objects+=@{apiVersion=$entry.api;kind=$entry.kind;metadata=@{name=$entry.name;namespace='oficina-staging'};spec=$entry.spec}
    }
    $arn='arn:aws:elasticloadbalancing:us-east-1:123456789012:targetgroup/oficina-phase3-staging-app/0123456789abcdef'
    $objects+=@{apiVersion='elbv2.k8s.aws/v1beta1';kind='TargetGroupBinding';metadata=@{name='oficina-app';namespace='oficina-staging'};spec=@{targetGroupARN=$arn;targetType='ip';serviceRef=@{name='oficina-app';port=8080}}}
    $outputs=@{environment=@{value='staging'};namespace=@{value='oficina-staging'};target_group_arn=@{value=$arn}}|ConvertTo-Json -Depth 10 -Compress
    $bundle=@{schemaVersion=1;environment='staging';appSourceCommit=$Release.sourceCommit;k8sSourceCommit=('c'*40);targetGroupArn=$arn;terraformOutputsJson=$outputs;terraformOutputsSha256=(Get-PrerequisiteHash $outputs);terraformOutputsBucket='fixture-artifact-bucket';terraformOutputsKey=('releases/k8s/staging/outputs/'+('c'*40)+'.json');terraformOutputsVersionId='fixture-output-version';migrationNetworkPolicySha256=$platform.MigrationNetworkPolicySha256;runtimePublicConfigMapSha256=$Release.runtimePublicConfigMapSha256;objects=$objects}
    $platform.StagingPrerequisitesJson=$bundle|ConvertTo-Json -Depth 70 -Compress
    $platform.StagingPrerequisitesSha256=Get-PrerequisiteHash $platform.StagingPrerequisitesJson
    foreach($object in $objects){
        $object.metadata.uid='12345678-1111-2222-3333-123456789abc';$object.metadata.resourceVersion='100'
        if($object.kind -ceq 'TargetGroupBinding'){$object.spec.ipAddressType='ipv4';$object.spec.vpcID='vpc-0123456789abcdef0'}
    }
    $receipt=@{schemaVersion=1;environment='staging';appSourceCommit=$Release.sourceCommit;k8sSourceCommit=('c'*40);bundleSha256=$platform.StagingPrerequisitesSha256;status='EXISTING_VERIFIED';objects=$objects}
    $platform.PlatformPrerequisitesReceiptJson=$receipt|ConvertTo-Json -Depth 70 -Compress
    $platform.PlatformPrerequisitesReceiptSha256=Get-PrerequisiteHash $platform.PlatformPrerequisitesReceiptJson
    $platform.PlatformPrerequisitesReceiptBucket='fixture-artifact-bucket'
    $platform.PlatformPrerequisitesReceiptKey="foundation-addons/manifests/staging/$($Release.sourceCommit)/readback.json"
    $platform.PlatformPrerequisitesReceiptVersionId='fixture-readback-version'
    $platform|ConvertTo-Json -Depth 70|Set-Content $PlatformPath -NoNewline
    $Release.platformInputsSha256=(Get-FileHash $PlatformPath).Hash.ToLowerInvariant()
}
