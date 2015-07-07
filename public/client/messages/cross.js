"use strict";

/**
 * @brief Receives the Crossings message from the server, containing the list
 * of crossings of an area. Adds them to the client's state, calls the drawing
 * method and subscribes to the roads of the area.
 * @param [in] crosses_message msg The Crossings message from the server, containing
 *             the list of crossings of a certain area.
 * @return void
 */
function msgCross(msg) {
    for (var i in msg.contained) {
        var name = msg.contained[i].name;
        city.crossings[name] = msg.contained[i];
        city.crossings[name].N = 'red';
        city.crossings[name].S = 'red';
        city.crossings[name].E = 'red';
        city.crossings[name].W = 'red';
        if (msg.contained[i].type == 'lights')
            msg.contained[i].greenRoads.forEach(function(road) {
                city.crossings[name][road] = 'green';
            });
    }
    var dest = msg.container;
    var re = /cross$/;
    dest = dest.replace(re, "");
    dest += "road";
    mys.send(JSON.stringify({dest: dest, request: "getContained", "callback": "road"}));

    updateCrosses();
}