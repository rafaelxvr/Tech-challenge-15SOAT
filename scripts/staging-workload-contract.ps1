Set-StrictMode -Version Latest

function Read-StagingWorkload([string]$Path, [object]$Release, [object]$Platform) {
    if ($Release.environment -isnot [string] -or $Release.mode -isnot [string] -or
        $Release.environment -cne 'staging' -or $Release.mode -cne 'FirstWriter') {
        throw 'Workload initialization supports staging FirstWriter only.'
    }
    $digest = $Release.PSObject.Properties['stagingWorkloadSha256']
    if ($null -eq $digest -or $digest.Value -isnot [string] -or
        $digest.Value -cnotmatch '\A[a-f0-9]{64}\z' -or (Get-AppFileHash $Path) -cne $digest.Value) {
        throw 'Staging workload must match its reviewed release digest.'
    }
    try {
        $list = ConvertFrom-Json -InputObject ([IO.File]::ReadAllText($Path)) -NoEnumerate
        if ($list -isnot [pscustomobject] -or $list.apiVersion -cne 'v1' -or $list.kind -cne 'List' -or
            $list.items -isnot [array] -or $list.items.Count -ne 3) { throw 'Invalid list.' }
        $objects = @{}
        foreach ($item in $list.items) {
            if ($item.kind -isnot [string] -or $item.kind -cnotin @('Deployment','ServiceAccount','HorizontalPodAutoscaler') -or
                $objects.ContainsKey($item.kind) -or $item.metadata.name -isnot [string] -or $item.metadata.name -cne 'oficina-app' -or
                $item.metadata.namespace -isnot [string] -or $item.metadata.namespace -cne 'oficina-staging') { throw 'Invalid identity.' }
            $objects[$item.kind] = $item
        }
        $deployment = $objects.Deployment; $account = $objects.ServiceAccount; $hpa = $objects.HorizontalPodAutoscaler
        $pod = $deployment.spec.template.spec
        foreach ($value in @($list.apiVersion, $list.kind, $deployment.apiVersion, $account.apiVersion, $hpa.apiVersion,
            $pod.containers[0].name, $pod.serviceAccountName, $deployment.spec.selector.matchLabels.'app.kubernetes.io/name',
            $deployment.spec.template.metadata.labels.'app.kubernetes.io/name', $hpa.spec.scaleTargetRef.apiVersion,
            $hpa.spec.scaleTargetRef.kind, $hpa.spec.scaleTargetRef.name, $hpa.spec.metrics[0].type,
            $hpa.spec.metrics[0].resource.name, $hpa.spec.metrics[0].resource.target.type)) {
            if ($value -isnot [string]) { throw 'Expected scalar string.' }
        }
        if ($deployment.apiVersion -cne 'apps/v1' -or $account.apiVersion -cne 'v1' -or $hpa.apiVersion -cne 'autoscaling/v2' -or
            $pod.containers -isnot [array] -or $pod.containers.Count -ne 1 -or $pod.containers[0].name -cne 'app' -or
            $pod.containers[0].image -isnot [string] -or $pod.containers[0].image -cne $Release.image -or
            $pod.serviceAccountName -cne 'oficina-app' -or
            $deployment.spec.selector.matchLabels.'app.kubernetes.io/name' -cne 'oficina-app' -or
            $deployment.spec.template.metadata.labels.'app.kubernetes.io/name' -cne 'oficina-app' -or
            $account.metadata.annotations.'eks.amazonaws.com/role-arn' -isnot [string] -or
            $account.metadata.annotations.'eks.amazonaws.com/role-arn' -cne $Platform.AppIrsaRoleArn) { throw 'Invalid workload binding.' }
        foreach ($setting in @(@{Name='SPRING_FLYWAY_ENABLED';Value='false'}, @{Name='SPRING_JPA_HIBERNATE_DDL_AUTO';Value='validate'})) {
            $envs = @($pod.containers[0].env | Where-Object name -CEQ $setting.Name)
            if ($envs.Count -ne 1 -or $envs[0].value -isnot [string] -or $envs[0].value -cne $setting.Value) { throw 'Unsafe schema writer.' }
        }
        if ($hpa.spec.minReplicas -isnot [long] -and $hpa.spec.minReplicas -isnot [int]) { throw 'Invalid minimum.' }
        if ($hpa.spec.maxReplicas -isnot [long] -and $hpa.spec.maxReplicas -isnot [int]) { throw 'Invalid maximum.' }
        if ($hpa.spec.metrics[0].resource.target.averageUtilization -isnot [long] -and
            $hpa.spec.metrics[0].resource.target.averageUtilization -isnot [int]) { throw 'Invalid utilization.' }
        if ($hpa.spec.minReplicas -ne 1 -or $hpa.spec.maxReplicas -ne 2 -or
            $hpa.spec.scaleTargetRef.apiVersion -cne 'apps/v1' -or $hpa.spec.scaleTargetRef.kind -cne 'Deployment' -or
            $hpa.spec.scaleTargetRef.name -cne 'oficina-app' -or $hpa.spec.metrics -isnot [array] -or $hpa.spec.metrics.Count -ne 1 -or
            $hpa.spec.metrics[0].type -cne 'Resource' -or $hpa.spec.metrics[0].resource.name -cne 'cpu' -or
            $hpa.spec.metrics[0].resource.target.type -cne 'Utilization' -or $hpa.spec.metrics[0].resource.target.averageUtilization -ne 60) { throw 'Invalid capacity.' }
        # Preserve the reviewed platform probes/resources/CSI/env; never start a writer before migration.
        $deployment.spec.replicas = 0
        $deployment.spec.strategy = [pscustomobject]@{type='Recreate'}
        return $objects
    } catch { throw 'Invalid reviewed staging workload bundle; expected only the APP Deployment, ServiceAccount and HPA.' }
}
