"use strict";

/**
 * @brief Receives the message that a vehicle is entering a park;
 *  moves the vehicle from the lane to the parking and sets to update the client
 * @param  [in] msg thing entered structure message
 * @param  [in] carId cars's identification 
 * @param  [in] placeId place's identification
 * @return void
 */
function msgCarPark(msg, carId, placeId) {
    var area = msg.container.id.substr(msg.container.id.indexOf('area/')+5);
    area = area.substr(0, area.indexOf('/'));

    if(city.areas[area].subscribed !== 0) {
        city.auto[carId] = msg;
        var auto = city.auto[carId];
        addToContainer(auto, city.parkings, placeId);
        auto.needsUpdate = 1;
    }
}