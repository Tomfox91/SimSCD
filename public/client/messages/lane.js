"use strict";

/**
 * @brief Receives the Lanes message from the server, containing the list
 * of lanes of the simulation, adds them to the client's state and calls the drawing method.
 * @param [in] lane_message msg The Lanes message from the server, containing the list of existing lanes.
 * @return void
 */
function msgLane(msg) {
    for (var i in msg.contained) {
        var dest = msg.container;
        var re = /\/lane$/;
        dest = dest.replace(re, "");
        msg.contained[i].queue = [];
        if (typeof city.lanes[dest] !== 'undefined')
            city.lanes[dest].lanes.push(msg.contained[i]);
        else {
            city.lanes[dest] = {road: dest, lanes: [msg.contained[i]]};
        }
    }

    updateLanes(dest);
}