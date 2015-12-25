/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import JSZip from 'jszip';

export default [
    '$scope', '$http', '$common', '$loading', '$table', '$filter', 'ConfigurationSummaryResource',
    function($scope, $http, $common, $loading, $table, $filter, Resource) {
        const ctrl = this;
        const igniteVersion = '1.5.0-b1';

        $loading.start('loading');

        Resource.read().then(({clusters}) => {
            $scope.clusters = clusters;

            $loading.finish('loading');

            const idx = sessionStorage.summarySelectedId || 0;

            $scope.selectItem(clusters[idx]);
        });

        $scope.panelExpanded = $common.panelExpanded;
        $scope.tableVisibleRow = $table.tableVisibleRow;
        $scope.widthIsSufficient = $common.widthIsSufficient;

        $scope.tabsServer = { activeTab: 0 };
        $scope.tabsClient = { activeTab: 0 };

        $scope.selectItem = (cluster) => {
            delete ctrl.cluster;

            if (!cluster)
                return;

            ctrl.cluster = cluster;

            $scope.cluster = cluster;
            $scope.selectedItem = cluster;

            sessionStorage.summarySelectedId = $scope.clusters.indexOf(cluster);
        };

        const updateTab = (cluster) => {
            if (!cluster)
                return;

            if (!$filter('hasPojo')(cluster) && $scope.tabsClient.activeTab === 3)
                $scope.tabsClient.activeTab = 0;
        };

        $scope.$watch('cluster', updateTab);

        // TODO IGNITE-2114: implemented as independent logic for download.
        $scope.downloadConfiguration = function() {
            const cluster = $scope.cluster;
            const clientNearCfg = cluster.clientNearCfg;

            const zip = new JSZip();

            zip.file('Dockerfile', ctrl.data.docker);

            const builder = $generatorProperties.generateProperties(cluster);

            if (builder)
                zip.file('src/main/resources/secret.properties', builder.asString());

            const srcPath = 'src/main/java/';

            const serverXml = 'config/' + cluster.name + '-server.xml';
            const clientXml = 'config/' + cluster.name + '-client.xml';

            zip.file(serverXml, $generatorXml.cluster(cluster));
            zip.file(clientXml, $generatorXml.cluster(cluster, clientNearCfg));

            zip.file(srcPath + 'factory/ServerConfigurationFactory.java', $generatorJava.cluster(cluster, 'factory', 'ServerConfigurationFactory', null));
            zip.file(srcPath + 'factory/ClientConfigurationFactory.java', $generatorJava.cluster(cluster, 'factory', 'ClientConfigurationFactory', clientNearCfg));

            zip.file(srcPath + 'startup/ServerNodeSpringStartup.java', $generatorJava.nodeStartup(cluster, 'startup', 'ServerNodeSpringStartup', '"' + serverXml + '"'));
            zip.file(srcPath + 'startup/ClientNodeSpringStartup.java', $generatorJava.nodeStartup(cluster, 'startup', 'ClientNodeSpringStartup', '"' + clientXml + '"'));

            zip.file(srcPath + 'startup/ServerNodeCodeStartup.java', $generatorJava.nodeStartup(cluster, 'startup', 'ServerNodeCodeStartup',
                'ServerConfigurationFactory.createConfiguration()', 'factory.ServerConfigurationFactory'));
            zip.file(srcPath + 'startup/ClientNodeCodeStartup.java', $generatorJava.nodeStartup(cluster, 'startup', 'ClientNodeCodeStartup',
                'ClientConfigurationFactory.createConfiguration()', 'factory.ClientConfigurationFactory', clientNearCfg));

            zip.file('pom.xml', $generatorPom.pom(cluster, igniteVersion).asString());

            zip.file('README.txt', $generatorReadme.readme().asString());
            zip.file('jdbc-drivers/README.txt', $generatorReadme.readmeJdbc().asString());

            for (const meta of ctrl.data.metadatas) {
                if (meta.keyClass)
                    zip.file(srcPath + meta.keyType.replace(/\./g, '/') + '.java', meta.keyClass);

                zip.file(srcPath + meta.valueType.replace(/\./g, '/') + '.java', meta.valueClass);
            }

            const blob = zip.generate({type: 'blob', mimeType: 'application/octet-stream'});

            // Download archive.
            saveAs(blob, cluster.name + '-configuration.zip');
        };
    }
];