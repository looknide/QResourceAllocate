#!/bin/bash
cd "$(dirname "$0")/QuantumRouting"
mvn -q clean compile
mvn -q exec:java -Dexec.mainClass=quantum.Main

# cd ~/project/ResourceAllocate/QuantumRouting
# mvn clean compile
# mvn exec:java -Dexec.mainClass=quantum.Main