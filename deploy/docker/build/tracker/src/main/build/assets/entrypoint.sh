#!/bin/bash
[[ -n $DEBUG ]] && set -x
set -eu

########################################################################################################################

my_bind="${REPOSITORY_SEARCH_ELASTIC_TRACKER_BIND:-"0.0.0.0"}"
my_port="${REPOSITORY_SEARCH_ELASTIC_TRACKER_PORT:-"8080"}"

my_management_bind="${REPOSITORY_SEARCH_ELASTIC_TRACKER_MANAGEMENT_BIND:-"127.0.0.1"}"
my_management_port="${REPOSITORY_SEARCH_ELASTIC_TRACKER_MANAGEMENT_PORT:-"8081"}"

repository_search_elastic_index_host="${REPOSITORY_SEARCH_ELASTIC_INDEX_HOST:-repository-search-elastic-index}"
repository_search_elastic_index_port="${REPOSITORY_SEARCH_ELASTIC_INDEX_PORT:-9200}"

repository_search_elastic_index_base="http://${repository_search_elastic_index_host}:${repository_search_elastic_index_port}"

repository_search_elastic_index_shards="${REPOSITORY_SEARCH_ELASTIC_INDEX_SHARDS:-1}"
repository_search_elastic_index_replicas="${REPOSITORY_SEARCH_ELASTIC_INDEX_REPLICAS:-1}"

repository_service_host="${REPOSITORY_SERVICE_HOST:-repository-service}"
repository_service_port="${REPOSITORY_SERVICE_PORT:-8080}"

repository_service_admin_pass="${REPOSITORY_SERVICE_ADMIN_PASS:-admin}"

### Wait ###############################################################################################################

until wait-for-it "${repository_search_elastic_index_host}:${repository_search_elastic_index_port}" -t 3; do sleep 1; done

until [[ $(curl -sSf -w "%{http_code}\n" -o /dev/null "${repository_search_elastic_index_base}/_cluster/health?wait_for_status=yellow&timeout=3s") -eq 200 ]]; do
	echo >&2 "Waiting for ${repository_search_elastic_index_host} ..."
	sleep 3
done

# Note: we intentionally do NOT wait for the repository here. The tracker only needs the
# repository in the document tracking phase; waiting for it lazily inside the application lets
# the ES-only work (index migration / startup hooks) start immediately while the repository boots.

########################################################################################################################

touch application.properties

sed -i -r 's|^[#]*\s*alfresco\.host=.*|alfresco.host='"${repository_service_host}"'|' "application.properties"
grep -q '^[#]*\s*alfresco\.host=' "application.properties" || echo "alfresco.host=${repository_service_host}" >>"application.properties"

sed -i -r 's|^[#]*\s*alfresco\.port=.*|alfresco.port='"${repository_service_port}"'|' "application.properties"
grep -q '^[#]*\s*alfresco\.port=' "application.properties" || echo "alfresco.port=${repository_service_port}" >>"application.properties"

sed -i -r 's|^[#]*\s*alfresco\.password=.*|alfresco.password='"${repository_service_admin_pass}"'|' "application.properties"
grep -q '^[#]*\s*alfresco\.password=' "application.properties" || echo "alfresco.password=${repository_service_admin_pass}" >>"application.properties"

sed -i -r 's|^[#]*\s*elastic\.host=.*|elastic.host='"${repository_search_elastic_index_host}"'|' "application.properties"
grep -q '^[#]*\s*elastic\.host=' "application.properties" || echo "elastic.host=${repository_search_elastic_index_host}" >>"application.properties"

sed -i -r 's|^[#]*\s*elastic.\index\.number_of_replicas=.*|elastic.index.number_of_replicas='"${repository_search_elastic_index_replicas}"'|' "application.properties"
grep -q '^[#]*\s*elastic.\index\.number_of_replicas=' "application.properties" || echo "elastic.index.number_of_replicas=${repository_search_elastic_index_replicas}" >>"application.properties"

sed -i -r 's|^[#]*\s*elastic\.index.\number_of_shards=.*|elastic.index.number_of_shards='"${repository_search_elastic_index_shards}"'|' "application.properties"
grep -q '^[#]*\s*elastic\.index.\number_of_shards=' "application.properties" || echo "elastic.index.number_of_shards=${repository_search_elastic_index_shards}" >>"application.properties"

sed -i -r 's|^[#]*\s*elastic\.port=.*|elastic.port='"${repository_search_elastic_index_port}"'|' "application.properties"
grep -q '^[#]*\s*elastic\.port=' "application.properties" || echo "elastic.port=${repository_search_elastic_index_port}" >>"application.properties"

sed -i -r 's|^[#]*\s*server\.address=.*|server.address='"${my_bind}"'|' "application.properties"
grep -q '^[#]*\s*server\.address=' "application.properties" || echo "server.address=${my_bind}" >>"application.properties"

sed -i -r 's|^[#]*\s*server\.port=.*|server.port='"${my_port}"'|' "application.properties"
grep -q '^[#]*\s*server\.port=' "application.properties" || echo "server.port=${my_port}" >>"application.properties"

sed -i -r 's|^[#]*\s*management\.server\.address=.*|management.server.address='"${my_management_bind}"'|' "application.properties"
grep -q '^[#]*\s*management\.server\.address=' "application.properties" || echo "management.server.address=${my_management_bind}" >>"application.properties"

sed -i -r 's|^[#]*\s*management\.server\.port=.*|management.server.port='"${my_management_port}"'|' "application.properties"
grep -q '^[#]*\s*management\.server\.port=' "application.properties" || echo "management.server.port=${my_management_port}" >>"application.properties"

########################################################################################################################

exec java ${JAVA_OPTS:-} -jar "edu_sharing-community-repository-plugin-elastic-tracker-${org.edu_sharing:edu_sharing-community-repository-plugin-elastic-tracker:jar.version}.jar" $@
