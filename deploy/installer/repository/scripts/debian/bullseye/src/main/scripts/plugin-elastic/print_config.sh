#!/bin/bash
set -e
set -o pipefail

########################################################################################################################

echo "#########################################################################"
echo ""
echo "plugin: elastic"
echo ""
echo "  index:"
echo ""
echo "    Host:              ${repository_search_elastic_index_host}"
echo "    Port:              ${repository_search_elastic_index_port}"
echo ""

########################################################################################################################