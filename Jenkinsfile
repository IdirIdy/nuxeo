/*
 * (C) Copyright 2019 Nuxeo (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     Antoine Taillefer <ataillefer@nuxeo.com>
 *     Thomas Roger <troger@nuxeo.com>
 */

dockerNamespace = 'nuxeo'
kubernetesNamespace = 'platform'
repositoryUrl = 'https://github.com/nuxeo/nuxeo'
testEnvironments= [
  'dev',
  'mongodb',
  'postgresql',
]

properties([
  [$class: 'GithubProjectProperty', projectUrlStr: repositoryUrl],
  [$class: 'BuildDiscarderProperty', strategy: [$class: 'LogRotator', daysToKeepStr: '60', numToKeepStr: '60', artifactNumToKeepStr: '5']],
  disableConcurrentBuilds(),
])

void setGitHubBuildStatus(String context, String message, String state) {
  if (env.DRY_RUN != "true") {
    step([
      $class: 'GitHubCommitStatusSetter',
      reposSource: [$class: 'ManuallyEnteredRepositorySource', url: repositoryUrl],
      contextSource: [$class: 'ManuallyEnteredCommitContextSource', context: context],
      statusResultSource: [$class: 'ConditionalStatusResultSource', results: [[$class: 'AnyBuildResult', message: message, state: state]]],
    ])
  }
}

String getMavenArgs() {
  def args = '-B -nsu'
  if (!isPullRequest()) {
    args += ' -Prelease'
  }
  return args
}

def isPullRequest() {
  return BRANCH_NAME =~ /PR-.*/
}

String getVersion() {
  return isPullRequest() ? getPullRequestVersion() : getReleaseVersion()
}

String getReleaseVersion() {
  String nuxeoVersion = readMavenPom().getVersion()
  String noSnapshot = nuxeoVersion.replace("-SNAPSHOT", "")
  String version = noSnapshot + '.0' // first version ever

  // find the latest tag if any
  sh "git fetch origin 'refs/tags/v${noSnapshot}*:refs/tags/v${noSnapshot}*'"
  def tag = sh(returnStdout: true, script: "git tag --sort=taggerdate --list 'v${noSnapshot}*' | tail -1 | tr -d '\n'")
  if (tag) {
    container('maven') {
      version = sh(returnStdout: true, script: "semver bump patch ${tag} | tr -d '\n'")
    }
  }
  return version
}

String getPullRequestVersion() {
  return "${BRANCH_NAME}-" + readMavenPom().getVersion()
}

String getDockerTagFrom(String version) {
  return version.tokenize('.')[0] + '.x'
}

void runFunctionalTests(String baseDir) {
  try {
    sh "mvn ${MAVEN_ARGS} -f ${baseDir}/pom.xml verify"
  } finally {
    try {
      archiveArtifacts allowEmptyArchive: true, artifacts: "${baseDir}/**/target/failsafe-reports/*, ${baseDir}/**/target/**/*.log, ${baseDir}/**/target/*.png, ${baseDir}/**/target/**/distribution.properties, ${baseDir}/**/target/**/configuration.properties"
    } catch (err) {
      echo hudson.Functions.printThrowable(err)
    }
  }
}

void dockerPull(String image) {
  sh "docker pull ${image}"
}

void dockerRun(String image, String command, String user = null) {
  String userOption = user ? "--user=${user}" : ''
  sh "docker run --rm ${userOption} ${image} ${command}"
}

void dockerTag(String image, String tag) {
  sh "docker tag ${image} ${tag}"
}

void dockerPush(String image) {
  sh "docker push ${image}"
}

void dockerDeploy(String imageName) {
  String fullImageName = "${dockerNamespace}/${imageName}"
  String fixedVersionInternalImage = "${DOCKER_REGISTRY}/${fullImageName}:${VERSION}"
  String latestInternalImage = "${DOCKER_REGISTRY}/${fullImageName}:${DOCKER_TAG}"
  String fixedVersionPublicImage = "${PUBLIC_DOCKER_REGISTRY}/${fullImageName}:${VERSION}"
  String latestPublicImage = "${PUBLIC_DOCKER_REGISTRY}/${fullImageName}:${DOCKER_TAG}"

  dockerPull(fixedVersionInternalImage)
  echo "Push ${latestInternalImage}"
  dockerTag(fixedVersionInternalImage, latestInternalImage)
  dockerPush(latestInternalImage)
  echo "Push ${fixedVersionPublicImage}"
  dockerTag(fixedVersionInternalImage, fixedVersionPublicImage)
  dockerPush(fixedVersionPublicImage)
  echo "Push ${latestPublicImage}"
  dockerTag(fixedVersionInternalImage, latestPublicImage)
  dockerPush(latestPublicImage)
}

/**
 * Replaces environment variables present in the given yaml file and then runs skaffold build on it.
 * Needed environment variables are generally:
 * - DOCKER_REGISTRY
 * - VERSION
 */
void skaffoldBuild(String yaml) {
  sh """
    envsubst < ${yaml} > ${yaml}~gen
    skaffold build -f ${yaml}~gen
  """
}

def buildUnitTestStage(env) {
  def isDev = env == 'dev'
  def testNamespace = "${TEST_NAMESPACE_PREFIX}-${env}"
  def redisHost = "${TEST_REDIS_RESOURCE}.${testNamespace}.${TEST_SERVICE_DOMAIN_SUFFIX}"
  return {
    stage("Run ${env} unit tests") {
      container("maven-${env}") {
        script {
          setGitHubBuildStatus("platform/utests/${env}", "Unit tests - ${env} environment", 'PENDING')
          try {
            echo """
            ----------------------------------------
            Run ${env} unit tests
            ----------------------------------------"""

            echo "${env} unit tests: install external services"
            // initialize Helm without Tiller and add local repository
            sh """
              helm init --client-only
              helm repo add ${HELM_CHART_REPOSITORY_NAME} ${HELM_CHART_REPOSITORY_URL}
            """
            // prepare values to disable nuxeo and activate external services in the nuxeo Helm chart
            sh 'envsubst < ci/helm/nuxeo-test-base-values.yaml > nuxeo-test-base-values.yaml'
            def testValues = '--set-file=nuxeo-test-base-values.yaml'
            if (!isDev) {
              sh "envsubst < ci/helm/nuxeo-test-${env}-values.yaml > nuxeo-test-${env}-values.yaml"
              testValues += " --set-file=nuxeo-test-${env}-values.yaml"
            }
            // install the nuxeo Helm chart into a dedicated namespace that will be cleaned up afterwards
            sh """
              jx step helm install ${HELM_CHART_REPOSITORY_NAME}/${HELM_CHART_NUXEO} \
                --name=${TEST_HELM_CHART_RELEASE} \
                --namespace=${testNamespace} \
                ${testValues}
            """
            // wait for Redis to be ready
            sh """
              kubectl rollout status statefulset ${TEST_REDIS_RESOURCE} \
                --namespace=${testNamespace} \
                --timeout=${TEST_DEFAULT_ROLLOUT_STATUS_TIMEOUT}
            """
            if (!isDev) {
              // wait for Elasticsearch to be ready
              sh """
                kubectl rollout status deployment ${TEST_ELASTICSEARCH_RESOURCE} \
                  --namespace=${testNamespace} \
                  --timeout=${TEST_ELASTICSEARCH_ROLLOUT_STATUS_TIMEOUT}
              """
              // wait for MongoDB or PostgreSQL to be ready
              def resourceType = env == 'mongodb' ? 'deployment' : 'statefulset'
              sh """
                kubectl rollout status ${resourceType} ${TEST_HELM_CHART_RELEASE}-${env} \
                  --namespace=${testNamespace} \
                  --timeout=${TEST_DEFAULT_ROLLOUT_STATUS_TIMEOUT}
              """
            }

            echo "${env} unit tests: run Maven"
            // prepare test framework system properties
            sh """
              CHART_RELEASE=${TEST_HELM_CHART_RELEASE} SERVICE=${env} NAMESPACE=${testNamespace} DOMAIN=${TEST_SERVICE_DOMAIN_SUFFIX} \
                envsubst < ci/mvn/nuxeo-test-${env}.properties > ${HOME}/nuxeo-test-${env}.properties
            """
            // run unit tests:
            // - in modules/core and dependent projects only (modules/runtime is run in dedicated stage)
            // - for the given environment (see the customEnvironment profile in pom.xml):
            //   - in an alternative build directory
            //   - loading some test framework system properties
            def testCore = env == 'mongodb' ? 'mongodb' : 'vcs'
            sh """
              mvn ${MAVEN_ARGS} -rf :nuxeo-core-parent \
                -Dcustom.environment=${env} \
                -Dcustom.environment.log.dir=target-${env} \
                -Dnuxeo.test.core=${testCore} \
                -Dnuxeo.test.redis.host=${redisHost} \
                test
            """

            setGitHubBuildStatus("platform/utests/${env}", "Unit tests - ${env} environment", 'SUCCESS')
          } catch(err) {
            setGitHubBuildStatus("platform/utests/${env}", "Unit tests - ${env} environment", 'FAILURE')
            throw err
          } finally {
            try {
              junit testResults: "**/target-${env}/surefire-reports/*.xml"
            } finally {
              echo "${env} unit tests: clean up test namespace"
              // uninstall the nuxeo Helm chart
              sh """
                jx step helm delete ${TEST_HELM_CHART_RELEASE} \
                  --namespace=${testNamespace} \
                  --purge
              """
              // clean up the test namespace
              sh "kubectl delete namespace ${testNamespace} --ignore-not-found=true"
            }
          }
        }
      }
    }
  }
}

pipeline {
  agent {
    label 'jenkins-nuxeo-platform-11'
  }
  environment {
    // force ${HOME}=/root - for an unexplained reason, ${HOME} is resolved as /home/jenkins though sh 'env' shows HOME=/root
    HOME = '/root'
    HELM_CHART_REPOSITORY_NAME = 'local-jenkins-x'
    HELM_CHART_REPOSITORY_URL = 'http://jenkins-x-chartmuseum:8080'
    HELM_CHART_NUXEO = 'nuxeo'
    TEST_HELM_CHART_RELEASE = 'test-release'
    TEST_NAMESPACE_PREFIX = "nuxeo-unit-tests-$BRANCH_NAME-$BUILD_NUMBER".toLowerCase()
    TEST_SERVICE_DOMAIN_SUFFIX = 'svc.cluster.local'
    TEST_REDIS_RESOURCE = "${TEST_HELM_CHART_RELEASE}-redis-master"
    TEST_ELASTICSEARCH_RESOURCE = "${TEST_HELM_CHART_RELEASE}-elasticsearch-client"
    TEST_DEFAULT_ROLLOUT_STATUS_TIMEOUT = '1m'
     // Elasticsearch might take longer
    TEST_ELASTICSEARCH_ROLLOUT_STATUS_TIMEOUT = '3m'
    NUXEO_IMAGE_NAME = 'nuxeo'
    SLIM_IMAGE_NAME = 'slim'
    // waiting for https://jira.nuxeo.com/browse/NXBT-3068 to put it in Global EnvVars
    PUBLIC_DOCKER_REGISTRY = 'docker.packages.nuxeo.com'
    MAVEN_OPTS = "$MAVEN_OPTS -Xms512m -Xmx3072m"
    MAVEN_ARGS = getMavenArgs()
    VERSION = getVersion()
    DOCKER_TAG = getDockerTagFrom("${VERSION}")
    CHANGE_BRANCH = "${env.CHANGE_BRANCH != null ? env.CHANGE_BRANCH : BRANCH_NAME}"
    CHANGE_TARGET = "${env.CHANGE_TARGET != null ? env.CHANGE_TARGET : BRANCH_NAME}"
    CONNECT_PREPROD_URL = 'https://nos-preprod-connect.nuxeocloud.com/nuxeo'
    // jx step helm install's --name and --namespace options require alphabetic chars to be lowercase
    PREVIEW_NAMESPACE = "nuxeo-preview-${BRANCH_NAME.toLowerCase()}"
    PERSISTENCE = "${!isPullRequest()}"
  }

  stages {
    stage('Set labels') {
      steps {
        container('maven') {
          echo """
          ----------------------------------------
          Set Kubernetes resource labels
          ----------------------------------------
          """
          echo "Set label 'branch: ${BRANCH_NAME}' on pod ${NODE_NAME}"
          sh """
            kubectl label pods ${NODE_NAME} branch=${BRANCH_NAME}
          """
        }
      }
    }

    stage('Update version') {
      steps {
        container('maven') {
          echo """
          ----------------------------------------
          Update version
          ----------------------------------------
          New version: ${VERSION}
          """
          sh """
            mvn ${MAVEN_ARGS} -Pdistrib,docker versions:set -DnewVersion=${VERSION} -DgenerateBackupPoms=false
            perl -i -pe 's|<nuxeo.platform.version>.*?</nuxeo.platform.version>|<nuxeo.platform.version>${VERSION}</nuxeo.platform.version>|' pom.xml
            perl -i -pe 's|org.nuxeo.ecm.product.version=.*|org.nuxeo.ecm.product.version=${VERSION}|' server/nuxeo-nxr-server/src/main/resources/templates/nuxeo.defaults
          """
        }
      }
    }

    stage('Git commit') {
      when {
        allOf {
          not {
            branch 'PR-*'
          }
          not {
            environment name: 'DRY_RUN', value: 'true'
          }
        }
      }
      steps {
        container('maven') {
          echo """
          ----------------------------------------
          Git commit
          ----------------------------------------
          """
          sh """
            git add .
            git commit -m "Release ${VERSION}"
          """
        }
      }
    }

    stage('Compile') {
      steps {
        setGitHubBuildStatus('platform/compile', 'Compile', 'PENDING')
        container('maven') {
          echo """
          ----------------------------------------
          Compile
          ----------------------------------------"""
          echo "MAVEN_OPTS=$MAVEN_OPTS"
          sh "mvn ${MAVEN_ARGS} -V -T0.8C -DskipTests install"
        }
      }
      post {
        success {
          setGitHubBuildStatus('platform/compile', 'Compile', 'SUCCESS')
        }
        failure {
          setGitHubBuildStatus('platform/compile', 'Compile', 'FAILURE')
        }
      }
    }

    stage('Run runtime unit tests') {
      steps {
        setGitHubBuildStatus('platform/utests/runtime/dev', 'Unit tests - runtime', 'PENDING')
        container('maven') {
          echo """
          ----------------------------------------
          Run runtime unit tests
          ----------------------------------------"""
          dir('modules/runtime') {
            sh "mvn ${MAVEN_ARGS} test"
          }
        }
      }
      post {
        always {
          junit testResults: '**/target/surefire-reports/*.xml'
        }
        success {
          setGitHubBuildStatus('platform/utests/runtime/dev', 'Unit tests - runtime', 'SUCCESS')
        }
        failure {
          setGitHubBuildStatus('platform/utests/runtime/dev', 'Unit tests - runtime', 'FAILURE')
        }
      }
    }

    stage('Run unit tests') {
      steps {
        script {
          def stages = [:]
          for (env in testEnvironments) {
            stages["Run ${env} unit tests"] = buildUnitTestStage(env);
          }
          parallel stages
        }
      }
    }

    stage('Package') {
      steps {
        setGitHubBuildStatus('platform/package', 'Package', 'PENDING')
        container('maven') {
          echo """
          ----------------------------------------
          Package
          ----------------------------------------"""
          sh "mvn ${MAVEN_ARGS} -f server/pom.xml -DskipTests install"
          sh "mvn ${MAVEN_ARGS} -f packages/pom.xml -DskipTests install"
        }
      }
      post {
        success {
          setGitHubBuildStatus('platform/package', 'Package', 'SUCCESS')
        }
        failure {
          setGitHubBuildStatus('platform/package', 'Package', 'FAILURE')
        }
      }
    }

    stage('Run "dev" functional tests') {
      steps {
        setGitHubBuildStatus('platform/ftests/dev', 'Functional tests - dev environment', 'PENDING')
        container('maven') {
          echo """
          ----------------------------------------
          Run "dev" functional tests
          ----------------------------------------"""
          runFunctionalTests('ftests')
        }
        findText regexp: ".*ERROR.*", fileSet: "ftests/**/log/server.log", unstableIfFound: true
      }
      post {
        always {
          junit testResults: '**/target/failsafe-reports/*.xml'
        }
        success {
          setGitHubBuildStatus('platform/ftests/dev', 'Functional tests - dev environment', 'SUCCESS')
        }
        failure {
          setGitHubBuildStatus('platform/ftests/dev', 'Functional tests - dev environment', 'FAILURE')
        }
      }
    }

    stage('Build Docker images') {
      steps {
        setGitHubBuildStatus('platform/docker/build', 'Build Docker images', 'PENDING')
        container('maven') {
          echo """
          ----------------------------------------
          Build Docker images
          ----------------------------------------
          Image tag: ${VERSION}
          """
          echo "Build and push Docker images to internal Docker registry ${DOCKER_REGISTRY}"
          // Fetch Nuxeo Tomcat Server and Nuxeo Content Platform packages with Maven
          sh "mvn ${MAVEN_ARGS} -f docker/pom.xml process-resources"
          skaffoldBuild('docker/skaffold.yaml')
        }
      }
      post {
        success {
          setGitHubBuildStatus('platform/docker/build', 'Build Docker images', 'SUCCESS')
        }
        failure {
          setGitHubBuildStatus('platform/docker/build', 'Build Docker images', 'FAILURE')
        }
      }
    }

    stage('Test Docker images') {
      steps {
        setGitHubBuildStatus('platform/docker/test', 'Test Docker images', 'PENDING')
        container('maven') {
          echo """
          ----------------------------------------
          Test Docker images
          ----------------------------------------
          """
          script {
            // nuxeo slim image
            def image = "${DOCKER_REGISTRY}/${dockerNamespace}/${SLIM_IMAGE_NAME}:${VERSION}"
            echo "Test ${image}"
            dockerPull(image)
            echo 'Run image as root (0)'
            dockerRun(image, 'nuxeoctl start')
            echo 'Run image as an arbitrary user (800)'
            dockerRun(image, 'nuxeoctl start', '800')

            // nuxeo image
            image = "${DOCKER_REGISTRY}/${dockerNamespace}/${NUXEO_IMAGE_NAME}:${VERSION}"
            echo "Test ${image}"
            dockerPull(image)
            echo 'Run image as root (0)'
            dockerRun(image, 'nuxeoctl start')
            echo 'Run image as an arbitrary user (800)'
            dockerRun(image, 'nuxeoctl start', '800')
          }
        }
      }
      post {
        success {
          setGitHubBuildStatus('platform/docker/test', 'Test Docker images', 'SUCCESS')
        }
        failure {
          setGitHubBuildStatus('platform/docker/test', 'Test Docker images', 'FAILURE')
        }
      }
    }

    stage('Git tag and push') {
      when {
        allOf {
          not {
            branch 'PR-*'
          }
          not {
            environment name: 'DRY_RUN', value: 'true'
          }
        }
      }
      steps {
        container('maven') {
          echo """
          ----------------------------------------
          Git tag and push
          ----------------------------------------
          """
          sh """
            #!/usr/bin/env bash -xe
            # create the Git credentials
            jx step git credentials
            git config credential.helper store

            # Git tag
            jx step tag -v ${VERSION}
          """
        }
      }
    }

    stage('Deploy Maven artifacts') {
      steps {
        setGitHubBuildStatus('platform/deploy', 'Deploy Maven artifacts', 'PENDING')
        container('maven') {
          echo """
          ----------------------------------------
          Deploy Maven artifacts
          ----------------------------------------"""
          sh "mvn ${MAVEN_ARGS} -Pdistrib -DskipTests deploy"
        }
      }
      post {
        success {
          setGitHubBuildStatus('platform/deploy', 'Deploy Maven artifacts', 'SUCCESS')
        }
        failure {
          setGitHubBuildStatus('platform/deploy', 'Deploy Maven artifacts', 'FAILURE')
        }
      }
    }

    stage('Upload Nuxeo Packages') {
      when {
        allOf {
          not {
            branch 'PR-*'
          }
          not {
            environment name: 'DRY_RUN', value: 'true'
          }
        }
      }
      steps {
        setGitHubBuildStatus('platform/upload/packages', 'Upload Nuxeo Packages', 'PENDING')
        container('maven') {
          echo """
          ----------------------------------------
          Upload Nuxeo Packages to ${CONNECT_PREPROD_URL}
          ----------------------------------------"""
          withCredentials([usernameColonPassword(credentialsId: 'connect-preprod', variable: 'CONNECT_PASS')]) {
            sh """
              PACKAGES_TO_UPLOAD="packages/nuxeo-*-package/target/nuxeo-*-package-*.zip"
              for file in \$PACKAGES_TO_UPLOAD ; do
                curl --fail -i -u "$CONNECT_PASS" -F package=@\$(ls \$file) "$CONNECT_PREPROD_URL"/site/marketplace/upload?batch=true ;
              done
            """
          }
        }
      }
      post {
        success {
          setGitHubBuildStatus('platform/upload/packages', 'Upload Nuxeo Packages', 'SUCCESS')
        }
        failure {
          setGitHubBuildStatus('platform/upload/packages', 'Upload Nuxeo Packages', 'FAILURE')
        }
      }
    }

    stage('Deploy Docker images') {
      when {
        allOf {
          not {
            branch 'PR-*'
          }
          not {
            environment name: 'DRY_RUN', value: 'true'
          }
        }
      }
      steps {
        setGitHubBuildStatus('platform/docker/deploy', 'Deploy Docker images', 'PENDING')
        container('maven') {
          echo """
          ----------------------------------------
          Deploy Docker images
          ----------------------------------------
          Image tag: ${VERSION}
          """
          echo "Push Docker images to public Docker registry ${PUBLIC_DOCKER_REGISTRY}"
          dockerDeploy("${SLIM_IMAGE_NAME}")
          dockerDeploy("${NUXEO_IMAGE_NAME}")
        }
      }
      post {
        success {
          setGitHubBuildStatus('platform/docker/deploy', 'Deploy Docker images', 'SUCCESS')
        }
        failure {
          setGitHubBuildStatus('platform/docker/deploy', 'Deploy Docker images', 'FAILURE')
        }
      }
    }

    stage('Deploy Preview') {
      when {
        not {
          branch 'PR-*'
        }
      }
      steps {
        setGitHubBuildStatus('nuxeo/preview', 'Deploy nuxeo preview', 'PENDING')
        container('maven') {
          dir('ci/helm/preview') {
            echo """
            ----------------------------------------
            Deploy Preview environment
            ----------------------------------------"""
            // first substitute environment variables in chart values
            sh """
              mv values.yaml values.yaml.tosubst
              envsubst < values.yaml.tosubst > values.yaml
            """
            script {
              boolean nsExists = sh(returnStatus: true, script: "kubectl get namespace ${PREVIEW_NAMESPACE}") == 0
              if (nsExists) {
                // Previous preview deployment needs to be scaled to 0 to be replaced correctly
                sh "kubectl -n ${PREVIEW_NAMESPACE} scale deployment nuxeo-preview --replicas=0"
              }
              sh """kubectl create secret generic kubernetes-docker-cfg \
                  --namespace=${PREVIEW_NAMESPACE} \
                  --from-literal=.dockerconfigjson="\$(kubectl --namespace ${kubernetesNamespace} get secret kubernetes-docker-cfg -ojsonpath='{.data.\\.dockerconfigjson}' | base64 --decode)" \
                  --type=kubernetes.io/dockerconfigjson --dry-run -o yaml | kubectl apply -f -"""
              // build and deploy the chart
              // To avoid jx gc cron job, reference branch previews are deployed by calling jx step helm install instead of jx preview
              sh """
                jx step helm build
                mkdir target && helm template . --output-dir target
                jx step helm install --namespace ${PREVIEW_NAMESPACE} --name ${PREVIEW_NAMESPACE} .
              """
              // We need to expose the nuxeo url by hand
              url = sh(returnStdout: true, script: "jx get urls -n ${PREVIEW_NAMESPACE} | grep -oP https://.* | tr -d '\\n'")
              echo """
                ----------------------------------------
                Preview available at: ${url}
                ----------------------------------------"""
            }
          }
        }
      }
      post {
        always {
          archiveArtifacts allowEmptyArchive: true, artifacts: '**/requirements.lock, **/target/**/*.yaml'
        }
        success {
          setGitHubBuildStatus('nuxeo/preview', 'Deploy nuxeo preview', 'SUCCESS')
        }
        failure {
          setGitHubBuildStatus('nuxeo/preview', 'Deploy nuxeo preview', 'FAILURE')
        }
      }
    }

    stage('JSF pipeline') {
      when {
        expression {
          // only trigger JSF pipeline if the target branch is master or a maintenance branch
          return CHANGE_TARGET ==~ 'master|\\d+\\.\\d+'
        }
      }
      steps {
        container('maven') {
          echo """
          ----------------------------------------
          Build JSF pipeline
          ----------------------------------------
          Parameters:
            NUXEO_BRANCH: ${CHANGE_BRANCH}
            NUXEO_COMMIT_SHA: ${GIT_COMMIT}
            NUXEO_VERSION: ${VERSION}
          """
          build job: "/nuxeo/nuxeo-jsf-ui-status/${CHANGE_TARGET}",
            parameters: [
              string(name: 'NUXEO_BRANCH', value: "${CHANGE_BRANCH}"),
              string(name: 'NUXEO_COMMIT_SHA', value: "${GIT_COMMIT}"),
              string(name: 'NUXEO_VERSION', value: "${VERSION}"),
            ], propagate: false, wait: false
        }
      }
    }
  }

  post {
    always {
      script {
        if (!isPullRequest()) {
          // update JIRA issue
          step([$class: 'JiraIssueUpdater', issueSelector: [$class: 'DefaultIssueSelector'], scm: scm])
        }
      }
    }
    success {
      script {
        if (!isPullRequest() && env.DRY_RUN != "true") {
          currentBuild.description = "Build ${VERSION}"
        }
      }
    }
  }
}
