The following scenario is an example of how to define the full path of an aicraft:

```txt
00:00:01.11>CRE,AC0001,B744,0.79101306,-2.59034810,182.55957037,300,450.00000000
00:00:01.11>ORIG,AC0001,0.79101306,-2.59034810
00:00:01.11>DEST,AC0001,-3.24887465,-2.77108288
00:00:01.11>ADDWPT,AC0001,-1.20341248,-2.67950591,36000.00000000,259.92459992
00:00:01.11>ADDWPT,AC0001,-1.25442610,-2.68192247,,259.92459992
00:00:01.11>ADDWPT,AC0001,-3.24887465,-2.77108288,0.0,450.00000000
00:00:01.11>LNAV,AC0001,ON
00:00:01.11>VNAV,AC0001,ON
```

The aircraft starts at an altitude of 300ft and will climb towards 36000ft. The altitude will be reached as soon as other altitude constraints allow it for climbs and as late as possible for descents (standard Top of Climb and Top of Descent logic). Additionally, the performance limits for the vertical speed will be controlled by the performance model. Such will limit climbing and descending speed of the aircraft.

The speed at each waypoint is the speed for the leg towards this waypoint.

Once the cruising phase is finished, aircraft will descend towards the ground.

See the [[Editing-flight-plans]] tutorial for more detailed background information on how to Edit this. 
