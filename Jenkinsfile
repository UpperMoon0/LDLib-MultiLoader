@Library('nstut-ci') _

nstutGradle(
    branch: '1.20.1',
    isolatedGradleCache: true,
    verify: 'python3 tools/jenkins_verify.py',
    targets: [[name: 'Fabric and Forge build/tests', java: 17, minutes: 90,
        command: './gradlew build --no-daemon --max-workers=1 --stacktrace -Dorg.gradle.internal.repository.max.retries=8 -Dorg.gradle.internal.repository.initial.backoff=1000 -Dorg.gradle.internal.http.connectionTimeout=120000 -Dorg.gradle.internal.http.socketTimeout=120000']],
    tests: '**/build/test-results/test/*.xml',
    artifacts: 'fabric/build/libs/*.jar,forge/build/libs/*.jar',
    diagnostics: '**/build/reports/**'
)
