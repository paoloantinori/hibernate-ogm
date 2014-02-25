#!/bin/sh

## check if docker daemon is available and running
docker ps > /dev/null 2> /dev/null
if [[ $? != 0 ]]; then
	echo "Docker daemon not running"
	exit -1
fi

# mongo port inside the container
MONGO_DOCKER_PORT_GUEST="27017"

echo "Starting temporary mongodb docker container"
MONGO_DOCKER_ID=$(docker run -d pantinor/centos-mongodb)

MONGO_DOCKER_IP=$(docker inspect -format '{{ .NetworkSettings.IPAddress }}' $MONGO_DOCKER_ID)

MONGODB_HOSTNAME=$MONGO_DOCKER_IP \
    MONGODB_PORT=$MONGO_DOCKER_PORT_HOST \
    mvn clean verify -DuseExternalMongoDb

echo "Stopping temporary mongodb docker container"
docker stop $MONGO_DOCKER_ID
#remove the container snapshot, since it uses space on hardrive
echo "Removing temporary mongodb docker container"
docker rm $MONGO_DOCKER_ID