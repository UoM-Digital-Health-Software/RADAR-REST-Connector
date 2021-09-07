# Copyright 2018 The Hyve
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

FROM gradle:7.2-jdk11 as builder

RUN mkdir /code
WORKDIR /code

ENV GRADLE_USER_HOME=/code/.gradlecache

COPY ./build.gradle ./settings.gradle /code/
COPY kafka-connect-rest-source/build.gradle /code/kafka-connect-rest-source/

RUN gradle downloadDependencies copyDependencies

COPY kafka-connect-fitbit-source/build.gradle /code/kafka-connect-fitbit-source/

RUN gradle downloadDependencies copyDependencies

COPY ./kafka-connect-rest-source/src/ /code/kafka-connect-rest-source/src

RUN gradle jar

COPY ./kafka-connect-fitbit-source/src/ /code/kafka-connect-fitbit-source/src

RUN gradle jar

FROM confluentinc/cp-kafka-connect-base:6.2.0-3-ubi8

MAINTAINER Joris Borgdorff <joris@thehyve.nl>

LABEL description="Kafka REST API Source connector"

ENV CONNECT_PLUGIN_PATH /usr/share/java/kafka-connect/plugins

# To isolate the classpath from the plugin path as recommended
COPY --from=builder /code/kafka-connect-rest-source/build/third-party/*.jar ${CONNECT_PLUGIN_PATH}/kafka-connect-rest-source/
COPY --from=builder /code/kafka-connect-fitbit-source/build/third-party/*.jar ${CONNECT_PLUGIN_PATH}/kafka-connect-fitbit-source/

COPY --from=builder /code/kafka-connect-rest-source/build/libs/*.jar ${CONNECT_PLUGIN_PATH}/kafka-connect-rest-source/
COPY --from=builder /code/kafka-connect-rest-source/build/libs/*.jar ${CONNECT_PLUGIN_PATH}/kafka-connect-fitbit-source/
COPY --from=builder /code/kafka-connect-fitbit-source/build/libs/*.jar ${CONNECT_PLUGIN_PATH}/kafka-connect-fitbit-source/

# Load topics validator
COPY ./docker/kafka-wait /usr/bin/kafka-wait

# Load modified launcher
COPY ./docker/launch /etc/confluent/docker/launch
