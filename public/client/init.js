"use strict";

/*
 *
 *  Project global variables, will be appended to the window global object.
 *  Runs as the first script on page load.
 *
 */

// SVG canvas utils
window.svg = d3.select("#cityView");
window.svgpanzoom = svgPanZoom("#cityView", {
    fit: false,
    minZoom: 10,
    maxZoom: 200
});
svgpanzoom.updateBBox().zoom(20).pan({x:50, y:50});

svgpanzoom.goto = function (pos) {
    var z = this.getZoom();
    var s = this.getSizes();
    var w = s.width;
    var h = s.height;

    svgpanzoom.pan({
        x: -z * pos.x + w/2,
        y: -z * pos.y + h/2
    });
}

// City global object, with containers for all entities. Since it holds state information of everything, 
// we refer hereafter to these data structures as the client's state.
window.city = {
    roads: {},
    crossings: {},
    auto: {},
    lanes: {},
    sidewalks: {},
    pedestrians: {},
    areas: {},
    busStops: {},
    parkings: {},
    buildings: {}
};

// Control panel helpers
window.selected_entity = null;
window.incumbent_pedestrian_id = null;


// Websocket and destination of the socket.
window.mys = new WebSocket("ws://" + location.host + "/ws");

// Operation mode, activates/deactivates console logging and debug information.
// Can be one of the values [DEVELOPMENT|PRODUCTION]
window.opMode = 'DEVELOPMENT';

// Splashscreen manager. Shows the splashscreen and removes it only until a fixed number of conditions is met.
window.splashManager = function() {
    var counter = 2;

    return {
        conditionMet: function() {
            counter--;

            if (!counter) {
                $('#splash').fadeOut();
            }
        }
    }
}();

setTimeout(function() { splashManager.conditionMet(); }, 3000);

// Communication initialization. As soon as the channel is open, requires basic information from the server.
mys.onopen = function() {
    try {
        mys.send(JSON.stringify({dest: "akka://infra/user/city/", request: "getTime", callback: "time"}));
        mys.send(JSON.stringify({dest: "akka://infra/user/city/area/", request: "getContained", callback: "areas"}));
        mys.send(JSON.stringify({dest: "akka://infra/user/city/eventBus/", request: "subscribe", callback: "system"}));
        $('#splashbutton').hide();
    }
    catch(e) {
        $('#splashtext').text('Error :' + e);
        $('#splashbutton').show();
    }
}
