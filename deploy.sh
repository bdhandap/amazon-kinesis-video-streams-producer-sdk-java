#!/usr/bin/env bash

aws lambda update-function-code --region us-west-2 --profile acuity-dev \
--function-name josvijay-java-producer \
--zip-file fileb://target/kinesisvideo-java-demo-1.0-SNAPSHOT.jar \
--publish
