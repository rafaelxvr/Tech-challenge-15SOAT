function Add-RuntimePublicFixture([System.Collections.IDictionary]$Release,[string]$Path) {
    $ca="-----BEGIN CERTIFICATE-----`nfixture-public-certificate`n-----END CERTIFICATE-----`n"
    $hash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($ca))).ToLowerInvariant()
    if(-not$Release.Contains('image')){$Release.image='123456789012.dkr.ecr.us-east-1.amazonaws.com/oficina@sha256:'+('a'*64)}
    if(-not$Release.Contains('bootstrapReview')){$Release.bootstrapReview=@{caSha256=$hash}}else{$Release.bootstrapReview.caSha256=$hash}
    $cm=@{apiVersion='v1';kind='ConfigMap';metadata=@{name='oficina-runtime-public-staging';namespace='oficina-staging';labels=@{'app.kubernetes.io/part-of'='oficina';'app.kubernetes.io/managed-by'='oficina-k8s-infra'}};data=@{'customer-public-keys.yaml'="security:`n  jwt:`n    customer:`n      public-keys:`n        test: |`n          -----BEGIN PUBLIC KEY-----`n          fixture-public-key`n          -----END PUBLIC KEY-----`n";'staff-issuer'='oficina-staging-staff';'staff-audience'='oficina-staging-api';'staff-key-id'='test';'customer-issuer'='oficina-staging-customer';'customer-audience'='oficina-staging-api';'notification-queue-url'='https://sqs.us-east-1.amazonaws.com/123456789012/oficina-phase3-staging-notifications.fifo';'history-zone'='UTC';'rds-ca.pem'=$ca}}
    $cm|ConvertTo-Json -Depth 12|Set-Content -LiteralPath $Path -NoNewline
    $Release.runtimePublicConfigMapSha256=(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()
}
