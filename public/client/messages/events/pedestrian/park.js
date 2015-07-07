"use strict";

/**
 * @brief Receives the message that a pedestrian is entering a parking;
 *  moves the pedestrian from the road to the parking to take the car
 * @param  [in] msg thing entered structure message
 * @param  [in] pedId pedestrian's identifier 
 * @param  [in] placeId place's identifier
 * @return void
 */
function msgPedPark(msg, pedId, placeId) {
    if (msg.selected) {
        incumbent_pedestrian_id = pedId;
        cp_selectVehicle(msg.thing.carId, 'car');
    }
    delete city.pedestrians[pedId];
}
