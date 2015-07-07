"use strict";

/**
 * @brief Receives the message that a pedestrian is entering a building;
 *  moves the pedestrian from the road to the building and sets to update the client
 * @param  [in] msg thing entered structure message
 * @param  [in] pedId pedestrian's identifier 
 * @param  [in] placeId place's identifier
 * @return void
 */
function msgPedBus(msg, pedId) {
    if (is_selected(msg.thing.id)) {
        incumbent_pedestrian_id = pedId;
        cp_selectVehicle(msg.bus.id, 'bus');
    }
    removeFromContainer(pedId, city.pedestrians);
    delete city.pedestrians[pedId];
    UpdateManager.ped = true;
}
