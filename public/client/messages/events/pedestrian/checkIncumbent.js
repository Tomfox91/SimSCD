"use strict";

/**
 * @brief Check if the current pedestrian is the incumbent one and then selects him
 * @param  [in] msg the event message
 * @param  [in] pedId pedestrian's identifier
 * @return void
 */
function checkIncumbent(msg, pedId) {
	if (pedId === incumbent_pedestrian_id) {
		cp_selectPedestrian(pedId);
		incumbent_pedestrian_id = null;
	}
}