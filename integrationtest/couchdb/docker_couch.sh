#!/bin/sh

## check if docker daemon is available and running
docker ps > /dev/null 2> /dev/null
if [[ $? != 0 ]]; then
	echo "Docker daemon not running"
	exit -1
fi

# couch port inside the container
# COUCH_DOCKER_PORT_GUEST="5984"

echo "Starting temporary couchdb docker container"
COUCH_DOCKER_ID=$(docker run -d pantinor/centos-couchdb)

COUCH_DOCKER_IP=$(docker inspect -format '{{ .NetworkSettings.IPAddress }}' $COUCH_DOCKER_ID)

COUCHDB_HOSTNAME=$COUCH_DOCKER_IP \
    mvn verify

echo "Stopping temporary couchdb docker container"
docker stop $COUCH_DOCKER_ID
#remove the container snapshot, since it uses space on hardrive
echo "Removing temporary couchdb docker container"
docker rm $COUCH_DOCKER_ID