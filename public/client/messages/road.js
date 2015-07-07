"use strict";

/**
 * @brief Receives the Roads message from the server, containing the list
 * of roads of the simulation, adds them to the client's state and calls the method to draw them
 * @param [in] road_message msg The Roads message from the server, containing the list of
 *             available roads of a certain area.
 * @return void
 */
function msgRoad(msg) {
    var expectedResponses = 0;
    for (var i in msg.contained) {
        city.roads[msg.contained[i].name] = msg.contained[i];
        if (msg.contained[i].isBusStop) {
            expectedResponses++;
            mys.send(JSON.stringify(
                {dest: msg.contained[i].name + '/busStop', request: 'getContained', callback: 'busStop'}));
        };
        if (msg.contained[i].hasPark) {
            expectedResponses++;
            mys.send(JSON.stringify(
                {dest: msg.contained[i].name + '/park', request: 'getContained', callback: 'park'}));
        };
        expectedResponses++;
        mys.send(JSON.stringify({dest: msg.contained[i].name + '/build', request: 'getContained', callback: 'build'}));
        mys.send(JSON.stringify({dest: msg.contained[i].name + '/lane', request: "getContained", callback: "lane"}));
    }
    
    var area = justArea(msg.container);
    SubscriptionManager.addMissing(area, expectedResponses);
    city.areas[area].subscribed = .5;
    updateRoads(msg);
}