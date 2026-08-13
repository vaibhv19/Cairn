@echo off
echo ===================================================
echo Building Cairn Service...
echo ===================================================
call mvn clean package -DskipTests
if errorlevel 1 (
    echo Building failed!
    exit /b %errorlevel%
)

echo ===================================================
echo Starting Cairn Cluster (3 instances)...
echo Node-A on Port 8081
echo Node-B on Port 8082
echo Node-C on Port 8083
echo ===================================================

start "Cairn Node-A (8081)" java -jar target/cairn-0.1.0-SNAPSHOT.jar --server.port=8081 --cairn.cluster.local-node-id=Node-A
start "Cairn Node-B (8082)" java -jar target/cairn-0.1.0-SNAPSHOT.jar --server.port=8082 --cairn.cluster.local-node-id=Node-B
start "Cairn Node-C (8083)" java -jar target/cairn-0.1.0-SNAPSHOT.jar --server.port=8083 --cairn.cluster.local-node-id=Node-C

echo ===================================================
echo Cluster nodes are starting in separate windows.
echo Use CTRL+C in those windows to stop them.
echo ===================================================
