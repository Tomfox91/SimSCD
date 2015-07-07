"use strict";

/*
 *
 *  Let updateCrosses be the function to update every cross present in the state of the client.
 *  This follows the general d3 update pattern. Additional details are documented in the Relazione Finale
 *
 */
function updateCrosses () {
    var source = city;
    var datiincroci = d3.values(city.crossings);
    var _svg = svg;


    // 1 - Data join
    var incroci = _svg.select("#crosses").selectAll(".cIncrocio")
    .data(datiincroci, function(d) { return d.name;  });

    
    // 2 - Enter Selection
    var incrociEnter = incroci.enter().append("g")
    .each(function (d) {d.contained = [];})
    .attr('class', 'cIncrocio');

    incrociEnter.append("rect")
    .attr('class', 'incrocio')
    .attr('width', 1)
    .attr('height', 1)
    .attr('x', function(d) { return d.pos.x - 0.5; })
    .attr('y', function(d) { return d.pos.y - 0.5; })
    .attr('data-contName', function(d) { return d.name});

    incrociEnter.filter(function(d) { return d.type == 'lights'; })
    .append('circle')
    .attr('class', 'semaforo')
    .attr('side', 'N')
    .attr('r', 0.1)
    .attr('cx', function(d) { return d.pos.x - 0.25; })
    .attr('cy', function(d) { return d.pos.y - 0.2; })
    .attr('data-contName', function(d) { return d.name});

    incrociEnter.filter(function(d) { return d.type == 'lights'; })
    .append('circle')
    .attr('class', 'semaforo')
    .attr('side', 'S')
    .attr('r', 0.1)
    .attr('cx', function(d) { return d.pos.x +0.25; })
    .attr('cy', function(d) { return d.pos.y + 0.2; })
    .attr('data-contName', function(d) { return d.name});

    incrociEnter.filter(function(d) { return d.type == 'lights'; })
    .append('circle')
    .attr('class', 'semaforo')
    .attr('side', 'W')
    .attr('r', 0.1)
    .attr('cx', function(d) { return d.pos.x - 0.2; })
    .attr('cy', function(d) { return d.pos.y +0.25; })
    .attr('data-contName', function(d) { return d.name});

    incrociEnter.filter(function(d) { return d.type == 'lights'; })
    .append('circle')
    .attr('class', 'semaforo')
    .attr('side', 'E')
    .attr('r', 0.1)
    .attr('cx', function(d) { return d.pos.x + 0.2; })
    .attr('cy', function(d) { return d.pos.y -0.25; })
    .attr('data-contName', function(d) { return d.name});

    
    // 2 - Update Selection
    incroci.filter(function(d) { return d.N || d.S || d.E || d.W; })
    .selectAll('.semaforo')
    .each(function(d) {
        var sem = d3.select(this);
        if (d[this.getAttribute('side')] == 'green') {
            sem.style('fill', 'rgba(0,255,0,0.5)');
        }
        else {
            sem.style('fill', 'rgba(255,0,0,0.5)');
        }

    });
    
    
    // 3 - Exit Selection
    incroci.exit()
        .remove();

}