"use strict";


/**
 * @brief Deletes every DOM object belonging to a given area,
 * and removes every entity belonging to that area from the client's state
 * @param [in] int area_id the id of the area you want to delete
 * @return void
 */
function deleteUnsubscribed(area_id) {

    /**
     * @brief Helper function to delete content belonging to the area from the client's state
     * @param [in] string container the entities container name
     * @param [in] string idname the name of the entities' identifier property
     * @param [in] int area_id the identifier of the area
     * @return void
     */
    function deleteStuff(container, idname, area_id) {
        Object.keys(city[container]).forEach(function(contained) {
            if ((city[container][contained][idname]).indexOf('area/' + area_id + '/') != -1)
                delete city[container][contained];
        });
    };
    
    function deleteJunctions(area_id) {
        var toPutInPendingList = []
        Object.keys(city['roads']).forEach(function(contained) {
            if ((city['roads'][contained]['to']).indexOf('area/' + area_id + '/') != -1 && (city['roads'][contained]['from']).indexOf('area/' + area_id + '/') == -1)
                toPutInPendingList.push(city['roads'][contained]);
        });
        updateRoads({contained: toPutInPendingList})
    };

    
    /**
     * @brief Helper function to delete content belonging to the area from the client's state
     * @param [in] string container the entities container name
     * @param [in] string container2 the entities subcontainer name
     * @param [in] string idname the name of the entities' identifier property
     * @param [in] int area_id the identifier of the area
     * @return void
     */
    function deleteStuffDeeper(container, container2, idname, area_id) {
        Object.keys(city[container]).forEach(function(contained) {
            if ((city[container][contained][container2][idname]).indexOf('area/' + area_id + '/') != -1) {
                delete city[container][contained];
            }
        });
    };

    
    /**
     * @brief Helper function to delete entities moving towards an entity of the given area
     * @param [in] string container the entities container name
     * @param [in] int area_id the identifier of the area
     * @return void
     */
    function deleteThingTo(container, area_id) {
        //city.roads[justRoad(pedone.container.id)].to
        Object.keys(city[container]).forEach(function(contained) {
            var thing = city[container][contained]
            if (thing['container'].type == 'lane' || thing['container'].type == 'sidewalk')
                if (city.roads[justRoad(thing['container'].id)].to.indexOf('area/' + area_id + '/') != -1)
                    delete city[container][contained]
        });
    }

    
    /**
     * @brief Helper function to set the hiddenDestination flag on roads that go towards crosses of the given area
     * @param [in] int area_id the identifier of the area
     * @return void
     */
    function setHiddenDestination(area_id) {
        Object.keys(city.roads).forEach(function(index) {
            var road = city.roads[index]
            if (road.to.indexOf('area/' + area_id + '/') != -1) { 
                road.hiddenDestination = 1
                updateRoads({contained: [road]})
            }
        })
    }

    
    /*
     *
     *
     *  Perform the deleting steps, from client's state and then from DOM. Finally forces a re-draw
     *
     */
    $("#subModal input.subscribe[data-target='" + area_id + "']").prop('checked', false);

    city.areas[area_id].subscribed = 0
    setHiddenDestination(area_id)
    deleteStuff('buildings', 'name',  area_id);
    deleteStuff('busStops', 'name',  area_id);
    deleteStuff('crossings', 'name',  area_id);
    deleteStuff('lanes', 'road',  area_id);
    deleteStuff('parkings', 'name',  area_id);
    deleteJunctions(area_id)
    deleteStuff('roads', 'from',  area_id);
    deleteStuffDeeper('pedestrians', 'container', 'id', area_id);
    deleteStuffDeeper('pedestrians', 'container', 'id', area_id);
    deleteThingTo('pedestrians', area_id);
    deleteStuffDeeper('auto', 'container', 'id', area_id);
    deleteThingTo('auto', area_id);
    deleteStuff('sidewalks', 'id',  area_id);

    $("[data-thingName*='area/"+area_id+"/']").remove();
    $("[data-contNameTwo*='area/"+area_id+"/']").remove();

    var i = city.areas[area_id]
    svg.select('#areas')
    .append('circle')
    .attr("class", "areaPlaceholder")
    .attr("data-target", i.name)
    .attr("cx", i.pos.x)
    .attr("cy", i.pos.y)
    .attr("r", 3);

    UpdateManager.vehImmediate()
    UpdateManager.pedImmediate()
    updateCrosses()
}