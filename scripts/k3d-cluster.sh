#!/usr/bin/env bash
# Creates the single-node k3d cluster the Helm chart runs on (M6), unless it exists, and imports the apps' images into
# it, so no registry is needed. Build the images first: ./mvnw -pl platform,authorization-service,simulator,gateway -am
# -DskipTests spring-boot:build-image. Run it again after rebuilding them. Delete the cluster with
# k3d cluster delete b2b-payments.
set -euo pipefail
cd "$(dirname "$0")/.."
cluster=b2b-payments

# The Clearing drop folder and the load test's cards file sit under var/, as in development; the pods reach them
# through the repository root, mounted into the node at /b2b-payments (hostPath volumes in the chart).
mkdir -p var/clearing var/load-test

if ! k3d cluster get "$cluster" > /dev/null 2>&1; then
  # k3s's bundled Traefik is off: the Gateway is the way in. The host ports go to k3s's ServiceLB through k3d's load
  # balancer, on this machine only: the Gateway on 8080 (as the platform in development), the simulator's demo on 8081
  # and Keycloak on 8180, the address in every token's issuer.
  k3d cluster create "$cluster" \
    --image rancher/k3s:v1.37.1-k3s1 \
    --k3s-arg '--disable=traefik@server:0' \
    --volume "$PWD:/b2b-payments@server:0" \
    --port '127.0.0.1:8080:8080@loadbalancer' \
    --port '127.0.0.1:8081:8081@loadbalancer' \
    --port '127.0.0.1:8180:8180@loadbalancer' \
    --wait
fi
# Creating the cluster switches kubectl to it; an existing one may not be the current context.
kubectl config use-context "k3d-$cluster"

images=(platform authorization-service simulator gateway)
refs=()
for image in "${images[@]}"; do refs+=("b2b-payments/$image:local"); done
k3d image import --cluster "$cluster" "${refs[@]}"
# k3d reports success even when the node failed to import, so check the node has each image.
present=$(docker exec "k3d-$cluster-server-0" crictl images)
for image in "${images[@]}"; do
  grep -q "b2b-payments/$image *local" <<< "$present" || { echo "the node lacks b2b-payments/$image:local" >&2; exit 1; }
done
echo "Imported ${images[*]} into $cluster"
