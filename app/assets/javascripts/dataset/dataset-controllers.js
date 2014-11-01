//===========================================================================
//    Copyright 2014 Delving B.V.
//
//    Licensed under the Apache License, Version 2.0 (the "License");
//    you may not use this file except in compliance with the License.
//    You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
//    Unless required by applicable law or agreed to in writing, software
//    distributed under the License is distributed on an "AS IS" BASIS,
//    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//    See the License for the specific language governing permissions and
//    limitations under the License.
//===========================================================================

var API_ACCESS_KEY = "secret"; // todo: find a better way

define(["angular"], function () {
    "use strict";

    var DatasetCtrl = function ($rootScope, $scope, $routeParams, $timeout, $location, datasetService, pageScroll) {
        var MAX_FOR_VOCABULARY = 12500;
        $scope.fileName = $routeParams.fileName;

        var absUrl = $location.absUrl();
        $scope.apiPrefix = absUrl.substring(0, absUrl.indexOf("#")) + "api/" + API_ACCESS_KEY;

        $scope.scrollTo = function (options) {
            pageScroll.scrollTo(options);
        };

        $scope.selectedNode = null;
        $scope.uniqueIdNode = null;
        $scope.recordRootNode = null;

        datasetService.datasetInfo($scope.fileName).then(function (datasetInfo) {

            $scope.datasetInfo = datasetInfo;

            if (datasetInfo.origin.type == 'origin-harvest') {
                // use a different record root and unique id
                $scope.recordContainer = "/pockets/pocket";
                $scope.recordRoot = $scope.recordContainer;
                $scope.uniqueId = $scope.recordRoot + "/@id";
                $scope.allowUniqueIdSet = false;
            }
            else if (datasetInfo.delimit && datasetInfo.delimit.recordRoot) {
                $scope.recordRoot = datasetInfo.delimit.recordRoot;
                $scope.uniqueId = datasetInfo.delimit.uniqueId;
                $scope.recordContainer = $scope.recordRoot.substring(0, $scope.recordRoot.lastIndexOf("/"));
                $scope.allowUniqueIdSet = datasetInfo.origin.type == 'origin-drop'; // only for changing unique id
            }
            else {
                $scope.allowUniqueIdSet = true
            }

            datasetService.index($scope.fileName).then(function (tree) {
                function sortKids(node) {
                    if (!node.kids.length) return;
                    node.kids = _.sortBy(node.kids, function (kid) {
                        return kid.tag.toLowerCase();
                    });
                    for (var index = 0; index < node.kids.length; index++) {
                        sortKids(node.kids[index]);
                    }
                }
                sortKids(tree);

                function setDelim(node) {
                    if (node.path == $scope.recordRoot) {
                        $scope.recordRootNode = node;
                    }
                    else if (node.path == $scope.uniqueId) {
                        $scope.uniqueIdNode = node;
                    }
                    else {
                        var rootPart = node.path.substring(0, $scope.recordContainer.length);
                        if (rootPart != $scope.recordContainer) {
                            console.log(rootPart + " != "+ $scope.recordContainer);
                            node.path = "";
                        }
                        else {
                            var recPath = node.path.substring($scope.recordContainer.length);
                            node.sourcePath = $rootScope.orgId + "/" + $scope.fileName + recPath;
                        }
                    }
                    for (var index = 0; index < node.kids.length; index++) {
                        setDelim(node.kids[index]);
                    }
                }
                if ($scope.recordRoot) {
                    setDelim(tree);
                    if (parseInt(datasetInfo.delimit.recordCount) <= 0 && $scope.uniqueIdNode) {
                        $scope.setUniqueIdNode($scope.uniqueIdNode); // trigger setting record count
                    }
                }

                datasetService.getSourcePaths($scope.fileName).then(function (data) {
                    function filterSourcePath(node, sourcePaths) {
                        if (node.sourcePath && sourcePaths.indexOf(node.sourcePath) < 0) {
                            delete node.sourcePath
                        }
                        for (var index = 0; index < node.kids.length; index++) {
                            filterSourcePath(node.kids[index], sourcePaths);
                        }
                    }
                    filterSourcePath(tree, data.sourcePaths);
                });

                $scope.tree = tree;

                function selectNode(path, node) {
                    if (!path.length) {
                        $scope.selectNode(node);
                    }
                    else {
                        var tag = path.shift();
                        for (var index = 0; index < node.kids.length; index++) {
                            if (tag == node.kids[index].tag) {
                                selectNode(path, node.kids[index]);
                                return;
                            }
                        }
                    }
                }
                if ($routeParams.path) selectNode($routeParams.path.substring(1).split('/'), { tag: '', kids: [$scope.tree]});
            });
        });

        $scope.goToDashboard = function () {
            $location.path("/dashboard");
            $location.search({});
        };

        $scope.goToTerms = function (node) {
            if (node && node != $scope.selectedNode) return;
            $location.path("/terms/" + $scope.fileName);
            $location.search({
                path: $routeParams.path,
                size: $scope.status.histograms[$scope.status.histograms.length - 1]
            });
            var lastPath = $routeParams.path.substring($routeParams.path.lastIndexOf("/"));
            $rootScope.addRecentTerms($scope.fileName + " (" + lastPath + ")", $location.absUrl())
        };

        function setActiveView(activeView) {
            $scope.activeView = activeView;
            $location.search({
                path: $routeParams.path,
                view: activeView
            });
        }

        function setActivePath(activePath) {
            $scope.activePath = activePath;
            $location.search({
                path: activePath,
                view: $routeParams.view
            });
        }

        $scope.selectNode = function (node, $event) {
            if ($event) $event.stopPropagation();
            if (node.lengths.length == 0 || node.path.length == 0) return;
            $scope.selectedNode = node;
            setActivePath(node.path);
            datasetService.nodeStatus($scope.fileName, node.path).then(function (data) {
                $scope.status = data;
                var filePath = node.path.replace(":", "_").replace("@", "_");
                $scope.apiPathUnique = $scope.apiPrefix + "/" + $scope.fileName + "/unique" + filePath;
                $scope.apiPathHistogram = $scope.apiPrefix + "/" + $scope.fileName + "/histogram" + filePath;
                $scope.sampleSize = 100;
                $scope.histogramSize = 100;
                switch ($routeParams.view) {
                    case 'sample':
                        $scope.fetchSample();
                        break;
                    case 'lengths':
                        $scope.fetchLengths();
                        break;
                    default:
                        $scope.fetchHistogram();
                        break;
                }
            });
        };

        $scope.setUniqueIdNode = function (node) {
            function selectFirstEmptyWithCount(node, count) {
                if (!node) return undefined;
                if (!node.lengths.length && node.count == count) {
                    return node;
                }
                else for (var index = 0; index < node.kids.length; index++) {
                    var emptyWithCount = selectFirstEmptyWithCount(node.kids[index], count);
                    if (emptyWithCount) return emptyWithCount;
                }
                return undefined;
            }

            var recordRootNode = selectFirstEmptyWithCount($scope.tree, node.count);
            if (recordRootNode) {
                $scope.recordRootNode = recordRootNode;
                $scope.uniqueIdNode = node;
                var body = {
                    recordRoot: $scope.recordRootNode.path,
                    uniqueId: $scope.uniqueIdNode.path,
                    recordCount: $scope.uniqueIdNode.count
                };
                datasetService.setRecordDelimiter($scope.fileName, body).then(function () {
                    console.log("record delimiter set");
                });
            }
        };

        $scope.fetchLengths = function () {
            $scope.sample = undefined;
            $scope.histogram = undefined;
            setActiveView("lengths");
        };

        $scope.fetchSample = function () {
            datasetService.sample($scope.fileName, $routeParams.path, $scope.sampleSize).then(function (data) {
                $scope.sample = data;
                $scope.histogram = undefined;
            });
            setActiveView("sample");
        };

        $scope.fetchHistogram = function () {
            datasetService.histogram($scope.fileName, $routeParams.path, $scope.histogramSize).then(function (data) {
                _.forEach(data.histogram, function (entry) {
                    var percent = (100 * entry[0]) / $scope.selectedNode.count;
                    entry.push(percent);
                });
                $scope.histogram = data;
                $scope.sample = undefined;
                $scope.histogramUnique = data.histogram[0] && data.histogram[0][0] == 1;
                $scope.histogramVocabulary = (!$scope.histogramUnique) && ($scope.status.uniqueCount < MAX_FOR_VOCABULARY);
            });
            setActiveView("histogram");
        };

        $scope.isMoreSample = function () {
            if (!($scope.status && $scope.status.samples)) return false;
            var which = _.indexOf($scope.status.samples, $scope.sampleSize, true);
            return which < $scope.status.samples.length - 1;
        };

        $scope.moreSample = function () {
            var which = _.indexOf($scope.status.samples, $scope.sampleSize, true);
            $scope.sampleSize = $scope.status.samples[which + 1];
            $scope.fetchSample();
        };

        $scope.isMoreHistogram = function () {
            if (!($scope.status && $scope.status.histograms)) return false;
            var which = _.indexOf($scope.status.histograms, $scope.histogramSize, true);
            return which < $scope.status.histograms.length - 1;
        };

        $scope.moreHistogram = function () {
            var which = _.indexOf($scope.status.histograms, $scope.histogramSize, true);
            $scope.histogramSize = $scope.status.histograms[which + 1];
            $scope.fetchHistogram();
        };

        $scope.getX = function () {
            return function (d) {
                return d[0];
            }
        };

        $scope.getY = function () {
            return function (d) {
                return d[1];
            }
        };

        $scope.getColor = function () {
            var lengthName = ["0", "1", "2", "3", "4", "5", "6-10", "11-15", "16-20", "21-30", "31-50", "50-100", "100-*"];
            var noOfColors = lengthName.length;
            var frequency = 4 / noOfColors;

            function toHex(c) {
                var hex = c.toString(16);
                return hex.length == 1 ? "0" + hex : hex;
            }

            function rgbToHex(r, g, b) {
                return "#" + toHex(r) + toHex(g) + toHex(b);
            }

            var colorLookup = {};
            for (var walk = 0; walk < noOfColors; ++walk) {
                var r = Math.floor(Math.sin(frequency * walk + 0) * (127) + 128);
                var g = Math.floor(Math.sin(frequency * walk + 1) * (127) + 128);
                var b = Math.floor(Math.sin(frequency * walk + 3) * (127) + 128);
                colorLookup[lengthName[walk]] = rgbToHex(r, g, b);
            }
            return function (d) {
                return colorLookup[d.data[0]];
            };
        };

    };

    DatasetCtrl.$inject = ["$rootScope", "$scope", "$routeParams", "$timeout", "$location", "datasetService", "pageScroll"];

    var TreeCtrl = function ($scope) {
        $scope.$watch('tree', function (tree) {
            if (tree) {
                $scope.node = tree;
            }
        });
    };

    TreeCtrl.$inject = ["$scope"];

    var NodeCtrl = function ($scope) {
    };

    NodeCtrl.$inject = ["$scope"];

    return {
        TreeCtrl: TreeCtrl,
        NodeCtrl: NodeCtrl,
        DatasetCtrl: DatasetCtrl
    };

});