![Route](images/bluesky_route.png)
This tutorial will show you how to create an aircraft and modify its route. Let's start BlueSky and create an aircraft:

    CRE KL001, B737,52,4,180,2000,220

Now let’s tell this level-flying plane to climb up to FL100 with a speed of 250 kts, and then freeze the simulation:

    KL001 ALT FL100
    KL001 SPD 250
    HOLD

Now we’re ready to start entering a route into the Flight Management System of this aircraft.
First we enter the destination using the ICAO identifier of the airport: (France, Paris de Gaulle):

    KL001 DEST LFPG

If we would start the simulation now with the `OP` command, the aircraft would start flying to the destination using the great circle, i.e. the shortest route. Optionally we can also enter the origin, let’s use Amsterdam:

    KL001 ORIG EHAM 
    KL001 ORIG EHAM RW06
    KL001 ORIG EHAM/RW06

We can now have the aircraft navigate directly to LFPG, in a straight line. But let's give the aircraft an FMS route to fly.

By double clicking on an aircraft (or entering its call sign or a POS command with the call sign) we see its route being displayed. Currently only one route is displayed at the time.
Now we can start entering actual waypoints with [[ADDWPT]], the syntax is:

    ADDWPT acid, wpname/position, [alt] , [spd] , [afterwp]

The aircraft id and command can be in reversed order (as with all commands as long as the aircraft exists, so not for CRE) and the altitude and speed are optional, and os is the afterwp argument. When only a speed constraint is entered use two commas between waypoint and speed. 

The speed is the speed for the leg towards this waypoint. The altitude will be reached as soon as other altitude constraints allow it for climbs and it will descend as late as possible for descents (standard Top of Climb/Top of Descent logic for civil airliners).

For the waypoint name also a lat-lon can be entered, e.g. by clicking on the display. For lat-lon it is also possible to type degrees’minutes’seconds’ like this: 52’16’13,004’15’40 (latitude: North is positive, longitude: East is positive).

Let us set a route for the aircraft, with several different syntaxes:

    KL001 ADDWPT TOLEN
    KL001 ADDWPT EBBR,FL100,250
    KL001 ADDWPT 50.3, 4.49
    KL001 ADDWPT LFAF,,0.80
    KL001 ADDWPT BSN FL200

By default, waypoints are added at the end of the route (but before the destination). You can use the [[AFTER]] command to insert a waypoint after a waypoint in the route:

    KL001 AFTER LFAF ADDWPT LFAH

The after waypoint can also be used as the final argument in the addwpt command, so after the speed constraint:

    KL001 ADDWPT LFJS, , , LFAH

The [[AT]] command allows viewing, editing or removing speed or altitude constraints:

    KL001 AT LFAF
    KL001 AT TOLEN SPD 240
    KL001 AT EBBR DEL ALT
    KL001 AT WOODY FL070/250
    KL001 AT TOLEN ----/----
    KL001 AT WOODY FL070/----
    KL001 DELWPT LFJS

During the flight it is possible to control which waypoint should be active (i.e. flown to) with the [[DIRECT]] command:

    KL001 DIRECT LFAH

A lat-lon waypoint will be given a waypoint-name using the call sign of the aircraft followed by sequence number with leading zeros, e.g. KL001. This name can then be used as reference in the [[AT]], [[AFTER]], [[DELWPT]] or last argument of the [[ADDWPT]] command. The complete route can be deleted with [[DELRTE]]: 

    DELRTE KL001

For any of the commands, clicking with the mouse on the displayed route will select the route waypoint. This is also possible when it is appropriate to enter a waypoint name on the command line. 

To make sure that the aircraft follows the route, the autopilot should be hooked up to the FMS. For heading, this is done by switching the [[LNAV]] (lateral navigation) mode on, for speed and altitude (if specified) this is done by switching [[VNAV]] (vertical navigation) mode on. 

VNAV can only be active when LNAV is on as well, so switch LNAV on first:

    KL001 LNAV ON
    KL001 VNAV ON

As soon as an altitude is given (`KL001 ALT FL380`), the VNAV mode is switched off again, and the ALT SEL mode is activated, and speed mode will go to SPD HOLD. AS soon as a speed command is given ('KL204 SPD 250') VANV will stay engaged for the altitude/VS part but speeds are now no lnger controlled by VNAV at the command of the basic autopilot modes SPD SEL and SPD HOLD.`KL204 VNAV OFF` also switches VNAV off, the aircraft continues in ALT-HOLD and SPD-HOLD.

Similarly, `LNAV OFF` disengages the LNAV mode, switching to HDG-HOLD, while a heading command (`KL001 HDG 90`) changes the lateral mode to HDG SEL (heading select).

The [[DEST]] and [[ADDWPT]] command switch the LNAV (and if possible the VNAV mode) on as well, to result in expected behaviour for users who are not setting autopilot modes themselves.

When the route pas been entered, running in FastFoward mode (the [[FF]] command) will show the aircraft flying the route in fast-time.

    OP
    FF

We will now see the aircraft flying the route that we designed.

Some useful tools while editing are DIST ('DIST KL204,EHAM') to find the distance and direction (from first yo second)  between two positions and CALC to do calculations ('CALC 60*nm').