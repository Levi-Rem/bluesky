# TRAIL: Trail
This function has two uses: either choose whether to display all aircraft trails, or change the trail color of a specific aircraft.

Trails are sequences of line segments between logged positions of aircraft. The parameter _dt_ can be used to set the time interval between loggings. 

**Usage:**

    TRAIL ON/OFF, [dt] OR TRAIL acid color

**Arguments 1:**

|Name|Type|Required|Description
|--------|------|---|---------------------------------------------------
ON/OFF|txt|yes|switch trails for all aircraft ON or OFF
dt|float|no| trail marker dt


**Arguments 2:**

|Name|Type|Required|Description
|--------|------|---|---------------------------------------------------
acid|txt|yes| Aircraft callsign 
color|txt|yes| Color BLUE/RED/YELLOW



[[Back to command reference.|Command Reference]]
