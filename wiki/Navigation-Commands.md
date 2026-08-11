In this section, we will review how to have our aircraft navigate to a specific location. First, let's create an aircraft at Schiphol airport, flying to the north.

    CRE KL001 B737 EHAM 0 FL150 200

For this tutorial, it is nice to see how our aircraft has been flying, so let's turn on the option to display breadcrumb trails:

    TRAIL ON

This function draws lines that display the previous locations of aircraft. Let's [[ZOOM]] in on the aircraft to see if it is working:

    PAN KL001
    ZOOM 4


![Screenshot_20221112_120337](https://user-images.githubusercontent.com/96892550/201493100-fe8c023b-3c6a-485a-b3da-f1bf5c875b45.png)


Now we have zoomed in enough that we can see the aircraft move on the screen. We see a cyan line drawn after the aircraft, indicating it's trajectory. Let's zoom out, now.

    ZOOM 1

The aircraft is now flying to the north from Amsterdam, but let's give it a different task. We can use the [[HDG]] command to order the aircraft to fly a specific heading:

    HDG KL001 270

This will have the aircraft fly to the west. We can now see the aircraft making a left turn in the simulation. We can also have the aircraft navigate to a specific location:

    ADDWPT KL001 EHRD

This command will have the aircraft fly to EHRD airport, near Rotterdam. Note that the [[ADDWPT]] command has many more options for syntax, but for now we keep it simple. We can even add a chain of waypoints:

    ADDWPT KL001 EBBR
    ADDWPT KL001 LFPO

These commands will send the aircraft to fly via the Rotterdam, Brussels and Paris airports. The final route of the aircraft looks like this:

IMAGE HERE!

Next tutorial: [[Sim Commands]]
