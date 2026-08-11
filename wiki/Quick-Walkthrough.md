On this page, we will do a quick walkthrough over several of the BlueSky commands. We will not go into detail about all the syntaxes, but rather show you coding examples.

Open BlueSky, and choose your favourite airport. Today, ours is Isle Of Man Airport, EGNS. Let's set the camera to go to that location.

    PAN EGNS
    ZOOM 1

Let's create an aircraft at the airport.

    CRE AC01 B744 EGNS 90 2500 200

![Accre](images/cre_ac01.png)
We now see an aircraft at the EGNS airport, heading to the east.
Let's increase the altitude and speed of the aircraft:

    AC01 ALT FL100
    AC01 SPD 220

We give the aircraft a route:

    AC01 ADDWPT EGCC
    AC01 ADDWPT EGLL FL300
    AC01 ADDWPT EHAM

When we click on the aircraft, we can see the route being displayed in magenta. 

![addwpt](images/ac01_addwpt.png)
This takes a bit long. Let's set the simulation to fast forward:

    FF

If we want, we can use HOLD or OP to pause the simulation or to set the time to normal again. We can follow the aircraft by dragging the map over the screen with the mouse, or by setting the screen to PAN to the aircraft:

    PAN AC01

We can see how the aircraft is flying to Amsterdam. Let's now delete the aircraft:

    DEL AC01

The aircraft is now removed from the simulation.