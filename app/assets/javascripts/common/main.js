/**
 * Common functionality.
 */
define(
    [
        "angular",
        "./services/fileUpload",
        "./services/playRoutes",
        "./filters",
        "./directives/scrollable",
        "./directives/fileModel"
    ],
    function (angular) {
        "use strict";

        return angular.module(
            "xml-ray.common",
            [
                "common.fileUpload",
                "common.playRoutes",
                "common.filters",
                "common.directives.scrollable",
                "common.directives.fileModel"
            ]
        );
    }
);
