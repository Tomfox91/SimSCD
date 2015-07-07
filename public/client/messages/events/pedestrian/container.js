"use strict";

/**
 * @brief Inserts an entity in the correct place
 * @param [in] thing the thing that has to been added
 * @param [in] cont the place where the thing has to been added
 * @param [in] placeId place's identifier
 * @return void
 */
function addToContainer(thing, cont, placeId) {
    if (cont[placeId])
        cont[placeId].contained.push(thing);
}

/**
 * @brief Removes an entity from a place
 * @param  [in] thingId entity's identifier
 * @param  [in] typeCont the container from where the entity has to been removed
 * @return void
 */
function removeFromContainer(thingId, typeCont) {
    var old = typeCont[thingId];
    if (old) {
        var oldCont = null;
        switch (old.container.type) {
            case 'busStop': oldCont = city.busStops; break;
            case 'build':  oldCont = city.buildings; break;
            case 'park':    oldCont = city.parkings; break;
            case 'cross':  oldCont = city.crossings; break;
        }

        if (oldCont && oldCont[old.container.id])
            oldCont[old.container.id].contained.forEach(function(p, i, c) {
                if (p.thing.id === thingId) 
                    c.splice(i, 1);
            });
    }
}