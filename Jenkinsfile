#!/usr/bin/env groovy

// Assume that the zip artefact has the same name as the Jenkins job
String target_file = "${env.JOB_BASE_NAME}.zip"

pipeline {
    agent { label 'docker' }

    environment {
        GIT_ID = "${sh(returnStdout: true, script: 'git describe --always').trim()}"
        BUILD_TIME = new Date().format('yyyyMMddHHmmss')
        ALIAS = "${GIT_ID}-${BUILD_TIME}"
    }

    stages {
        stage('Set build details') {
            steps {
                script {
                    currentBuild.description = "version - ${ALIAS}"
                }
            }
        }
        stage('Build artefact') {
            agent {
                dockerfile {
                    // Cache sbt dependencies
                    args "-v /tmp/.sbt:/root/.sbt -u root:root"
                }
            }
            steps {
                sh(script: "sbt assembly")
                stash(
                    name: 'artefact',
                    includes: target_file
                )
            }
        }
        stage('Run tests') {
            agent {
                dockerfile {
                    // Cache sbt dependencies
                    args "-v /tmp/.sbt:/root/.sbt -u root:root"
                }
            }
            steps {
                sh(script: "sbt test")
                sh("echo ${env.GIT_BRANCH}")
                script {
                    def gitbranch = sh(
                        script: 'git rev-parse --abbrev-ref HEAD',
                        returnStdout: true
                    ).trim()

                    echo "Branch: ${gitbranch}"
                }
            }
        }
        stage('test skipping steps v1') {
            steps {
                script{
                    if (env.GIT_BRANCH == 'origin/main') {
                        echo 'branch is main'
                    } else if (env.GIT_BRANCH != 'someBranch') {
                        echo 'branch is not called someBranch'
                    }
                }
            }
        }
        stage('test skipping steps v2') {
            when {
                expression {
                    env.GIT_BRANCH == 'origin/main'
                }
            }
            steps {
              echo 'still on branch main'
            }
        }
        stage('Generate sha256') {
            steps {
                unstash(name: 'artefact')
                sh("openssl dgst -sha256 -binary ${target_file} | openssl enc -base64 > ${env.JOB_BASE_NAME}.zip.base64sha256")
            }
        }
        stage('Upload to s3') {
            steps {
                sh(
                    """
                    aws s3 cp ${target_file} \
                        s3://mdtp-lambda-functions-integration/${env.JOB_BASE_NAME}/${env.JOB_BASE_NAME}_${ALIAS}.zip \
                        --acl=bucket-owner-full-control --only-show-errors
                    aws s3 cp ${env.JOB_BASE_NAME}.zip.base64sha256 \
                        s3://mdtp-lambda-functions-integration/${env.JOB_BASE_NAME}/${env.JOB_BASE_NAME}_${ALIAS}.zip.base64sha256 \
                        --content-type text/plain --acl=bucket-owner-full-control --only-show-errors
                    """
                )
            }
        }
        stage('Deploy to Integration') {
            steps {
                build(
                    job: 'api-platform-admin-api/deploy_lambda_version',
                    parameters: [
                        [$class: 'StringParameterValue', name: 'ARTEFACT', value: env.JOB_BASE_NAME],
                        [$class: 'StringParameterValue', name: 'HASH', value: ALIAS],
                        [$class: 'BooleanParameterValue', name: 'ACTIVATE_INTEGRATION', value: true],
                    ]
                )
            }
        }
    }
}