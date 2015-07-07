"use strict";

/**
 * @brief Determines if the currently selected object is a valid object and has the given ID.
 * @param [in] String id the ID of the object we want to query about.
 * @return bool true if the entity with the given ID is selected, false otherwise.
 */
function is_selected(id) {
    return (selected_entity && selected_entity.data.thing && selected_entity.data.thing.id === id);
}

var update_cp = (function() {

    var selected  = $('#e_selected');
    var type      = $('#e_type');
    var contained = $('#c_entities');

    var oldContainedString = "";
    var oldContainedCount = -1;

    /**
     * @brief Util to use the correct pluralization of "passenger"
     * @param [in] int n the number of current passengers
     * @return String the number of passengers, and the name "passenger" with the correct pluralization
     */
    function passengers(n) {
        return n===1 ? '1 passenger' : n + ' passengers';
    }

    /**
     * @brief Util to print a road entity as a <li> HTML element
     * @param [in] road_entity ent the road entity to print
     * @return String a valid <li> element with information on the entity
     */
    function thingToLI(ent) {
        var thing = ent.thing;
        var out = '<li>';
        if (thing.id === incumbent_pedestrian_id) {
            out += '<strong>' + thing.id + '</strong>';
        } else {
            out += '<a href="#" class="selectThing" ' +
                'data-thingType="' + thing.type + '" ' +
                'data-thingId="' + thing.id + '" ' +
                '>' + thing.id + '</a>';
        }
        if (thing.type === 'car' || thing.type === 'bus') out += ' <small>' + thing.type + '</small>';
        if (thing.type === 'bus') out += ' <small>(' + passengers(thing.occupants.length) + ')</small>';
        if (thing.info) out += ' <small>' + thing.info + '</small>';
        return out += '</li>';
    }
    
    /**
     * @brief Util to get the list of road entities contained by a road container
     * @param [in] road_container data the road container we want to get the contained entities
     * @param [in] String type the type of road container
     * @return Object with data fields veichles and pedestrians being arrays of the given contained entities
     */
    function containedEntities(data, type) {
        switch (type) {
            case "roads":
                return {
                    vehicles: city.lanes[data.name].lanes.map(function(l) {return l.queue;})
                        .reduce(function(a, b) {return a.concat(b);}),

                    pedestrians:(['/SWf', '/SWr'].map(function(sw) {
                        if (city.sidewalks[data.name + sw]) 
                            return city.sidewalks[data.name + sw].queue;
                        else return [];
                    }).reduce(function(a, b) {return a.concat(b);}))
                };
            break;

            case "crossings": case "busStops": case "parkings": case "buildings":
                var things = data.contained;
                return {
                    vehicles: things.filter(function(t) {return t.thing.type === 'bus' || t.thing.type === 'car';}),
                    pedestrians: things.filter(function(t) {return t.thing.type === 'pedestrian';})
                };
            break;

            case "buss":
                return {
                    vehicles: [],
                    pedestrians: city.auto[data.thing.id].thing.occupants
                        .map(function(id) {return {thing: {id: id, type: 'passenger'}};})
                }
            break;

            case "cars":
                return {
                    vehicles: [],
                    pedestrians: 
                        (city.auto[data.thing.id].container.type === 'park' ? [] 
                            : [{thing: {
                                id: data.thing.owner,
                                type: 'passenger'}}])
                }
            break;

            default:
                return {
                    vehicles: [],
                    pedestrians: []
                };
            break;
        }
    }

    /**
     * @brief Util to print the name of an entity
     * @param [in] road_container data the entity we want the name of
     * @param [in] String type the type of the entity
     * @return String with the name of the entity
     */
    function displayName(data, type) {
        if (type === 'roads') {
            return data.fullName + ' <small>' + data.name + '</small>';
        } else if (data.thing && data.thing.carId) {
            return data.thing.id + ' <small> car: ' +
                '<a href="#" class="selectThing" ' +
                'data-thingType="car" ' +
                'data-thingId="' + data.thing.carId + '" ' +
                '>' + data.thing.carId + '</a>' +
                data.thing.info.substr(14) + '</small>';
        } else if (data.thing && data.thing.info) {
            return data.thing.id + ' <small>' + data.thing.info + '</small>';
        } else if (data.thing && data.thing.owner) {
            return data.thing.id + ' <small> owner: ' + data.thing.owner + '</small>';
        } else {
            return data.name || data.thing.id;
        }
    }

    return function() {
        if (!selected_entity) {
            selected.text("Nothing!");
            type.text("No type");
            contained.text("");
            oldContainedCount = -1;
            oldContainedString = "";

        } else {
            type.text(selected_entity.type.slice(0, -1));

            selected.html(displayName(selected_entity.data, selected_entity.type));

            var containedData = containedEntities(selected_entity.data, selected_entity.type);

            var containedList = containedData.pedestrians.concat(containedData.vehicles)
                .map(function(t) {return t.thing.id;});

            var containedString = containedList.join('~') + '|' + incumbent_pedestrian_id;

            if (oldContainedCount !== containedList.length
                || oldContainedString !== containedString) {

                contained.html('<small>Vehicles </small><ul>' +
                    (containedData.vehicles.map(thingToLI).join('')) + '</ul>' +
                    '<small>People </small><ul>' +
                    (containedData.pedestrians.map(thingToLI).join('')) + '</ul>');

                oldContainedCount = containedList.length;
                oldContainedString = containedString;
            }
        }
    }
})();
