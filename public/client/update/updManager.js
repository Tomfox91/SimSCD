"use strict";

/**
 * @brief Object that holds the update status of the entities
 * @type {Object}
 */
var UpdateManager = {
    veh: false,
    ped: false,
    cp: false
}

!((function() {
    
    /**
     * @brief Updates the parts of the control panel that need updating, according to the UpdateManager variables' state
     * @return void
     */
    function ticker() {
        if (UpdateManager.veh) {
            UpdateManager.veh = false;
            try {
                updateCars();
            }
            catch (e) {}
        }
        if (UpdateManager.ped) {
            UpdateManager.ped = false;
            try{
                updatePedestrians()
            }
            catch (e) {}
        }
        if (UpdateManager.cp) {
            UpdateManager.cp = false;
            update_cp();
        }
    }

    UpdateManager.start = function() {
        d3.timer(ticker);
        delete UpdateManager.start;
    }

    UpdateManager.vehImmediate = function() {
        UpdateManager.veh = false;
        updateCars();
    }

    UpdateManager.pedImmediate = function() {
        UpdateManager.ped = false;
        updatePedestrians();
    }

    UpdateManager.cpImmediate = function() {
        UpdateManager.cp = false;
        update_cp();
    }
})());
