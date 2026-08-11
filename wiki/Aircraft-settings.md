# Creating a single aircraft
Let's start by creating an aircraft. To do this, we can enter the command CRE in the command line. We enter "HELP CRE" to get information about CRE. (For info about HELP: [[Getting Help]]).

We see the result in the Text Output:

    HELP CRE
    CRE acid, type, lat, lon, hdg, alt, spd
    Create an aircraft

This means that we need to supply 7 arguments in addition to [[CRE]], if we want to create an aircraft. These are the aircraft identifier string, type, coordinates and speed vector. 

Let's create an aircraft, flying above the North Sea, to the south. We enter in the Command Line:

    CRE KL001 B737 52 3 180 FL100 300

![Create](images/bluesky_create_aircraft.png)

This command is good to build our aircraft, with the following settings:
* Identifier KL001
* Type Boeing 737
* Latitude 52 degrees
* Longitude 3 degrees
* Heading 180 (to the south)
* Flight Level 100
* Speed 300 kts

The result of this command is to build our aircraft. We will see a green pointer icon, representing the aircraft and flying to the south:

![Create](images/bluesky_create_aircraft_output.png)

In the Simulation State view, we notice that the simulation time starts running: parameter **t** on the left. Also, check that the counter **Aircraft** is now 1.

Let's create a second aircraft, but in a slightly different way:

    CRE KL002 B737 EHAM 0 FL150 300

We see that a second aircraft is now created. But the location is now not given in lat-lon coordinates, but by the string EHAM - indicating the location of Amsterdam airport.

A third option is available as well:

    CRE KL003 B737 KL001 90 FL150 300

This aircraft is now created on the location of the first aircraft, KL001. Only the lat-lon coordinates of KL001 are used, we still have to provide the altitude. 

Also runways can be used as location, in similar fashion like lat-lon as two arguments:

    CRE KL004 , B737, EHAM, RW06,*,100,200

Here the runway heading will be entered as heading because of the use of the wildcard ( * ) as heading.

The screen now looks the following:

![Cr14](images/cre_kl001-kl004.png)

The last aircraft creation option that we will discuss now, is to click the screen. First, enter the following:

    CRE KL005 B737

Now, BlueSky is expecting us to enter the coordinates. Click anywhere on the screen to choose a location, and we see that the lat-lon coordinates are automatically entered in the Command Line. Now finish the line by adding the heading, altitude and speed parameters and press enter.

# Deleting aircraft

We can delete an aircraft from the simulation with the [[DEL]] command. Let's remove all our aircraft now:

    DEL KL002
    DEL KL003
    DEL KL004
    DEL KL005

Only the first aircraft, KL001, should be flying now.

# Changing aircraft parameters

KL001 has covered some distance since we started the tutorial. Let us check at what location it is now. For this, we can use the [[POS]] command:

    POS KL001

We see that the information about the current state of KL001 is displayed in the Text Output. Let's change this:

    MOVE KL001 53 3

![move](images/move_kl001.png)
The aircraft now gets transported to the north. If we use POS KL001 again, we see that other parameters such as the flight level are not changed. Note that if we use:

    HELP MOVE

We see that for MOVE we can also enter the desired altitude, heading, speed and vertical speed - if we want to. But what if we only wanted to change the altitude?

    MOVE KL001 KL001 FL300

This command will move the aircraft to the position, where lat-lon is defined by the position of KL001. This is similar to the syntax for creating aircraft at a specific location. We set the altitude with FL300.

![move2](images/move_kl001_fl300.png)

Next tutorial: [[Navigation Commands]]