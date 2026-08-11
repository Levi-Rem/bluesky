Let's look at the commands that you can use to control your simulations. 

We will cover the commands that allow you to pause, start, reset, fast forward and stop your simulations. Furthermore, we will look at how to set the simulated (clock) time and simulation time step. 

First run the BlueSky. Notice the values of the parameters in the Simulation State panel.

![Simulation_state](images/bluesky_simulation_state.png)

In this tutorial we will discuss the following parameters:
 
* **t** - simulation time
* **delta t** - simulation time step
* **Speed** - multiplication factor for (fast-time) simulations
* **UTC** - simulated clock time
* **Mode** - simulation mode

The simulation time **t** as well as the clock time **UTC** are at 00:00:00, which is logical since we have not created any aircraft yet and there is nothing to simulate. You can also see that the simulation **Mode** is _Init_ or in other words the simulation is in its initial state.  

To start your simulation, let's create an aircraft with the call sign KL001 by typing the following command in the command line

    CRE KL001 B737 52 3 180 FL100 300

You can find more about the aircraft creation command at [[Aircraft settings]].

You should see a newly created aircraft as shown in the figure below.

![Create_aircraft](images/bluesky_create_aircraft_output.png)

Notice how the simulation time **t** and the clock time **UTC** started increasing. This indicates that your simulation is running. Additionally, you can see that **Mode** has changed to _Operate_. 


Now let's explore the commands you can use to control your simulation.  

# Pausing simulation 

First let your simulation run for 10 seconds. Then type 

    HOLD 

in the command line. Notice how the simulation time **t** and the clock time **UTC** have stopped and the simulation **Mode** has changed to _Hold_. You have paused your simulation :smiley:

Alternatively, you can pause your simulation by pressing the Hold button in the Simulation Controls panel.

![Simulation_controls](images/bluesky_simulation_controls.png)

# Starting Simulation

After some time your simulation has being paused, you probably would like to continue it. Type

    OP 

in the command line or press button **Op** in the Simulation Controls panel. Notice how the simulation time **t** and the clock time **UTC** started increasing and the simulation **Mode** has changed to _Operate_.

# Fast Forwarding Simulation 

While it is a lot of fun to observe your simulation running in the real time :grinning:, you might get a bit tired while waiting for your 2 hour simulation to finally get finished :sleeping:. Luckily you can fast-forward your simulation. 

Similar to the previously discussed commands, you can fast-forward your simulation by typing 

    FF 

in the command line or by pressing the **Fast** button in the Simulation Controls panel. 

Keep in mind that you can fast-forward only a running simulation. Therefore, if your simulation is paused and you would like to fast-forward it, first restart it with Op command/button and then fast-forward it.   

You can verify that you fast-forward your simulation by checking the **Speed** parameter in the Simulation State panel and by noticing how fast your aircraft left the visible part of the map :airplane:.   

![FastForward](images/fast_forward.png)

The value of the **Speed** parameter indicates the multiplication factor for the fast-time simulation. In the example shown above it is 76.9. In general, this value will be large, but may slightly vary.  

When the simulation is fast forwarded with FF command without any arguments it will run forever unless interrupted/stopped by another command or the BlueSky window is closed. 

Additionally, you could specify by how many seconds your simulation should be fast forwarded. Typing 

    FF 10

in the command line will fast forward your simulation by 10 seconds. Alternatively, you could use **Fast 10** button in the Simulation Controls panel for this purpose. 

You can change the value of the **Speed** parameter yourself by typing 

    DTMULT 10

in the command line to set the **Speed** parameter to 10. Note, however, that you cannot change this parameter value while command FF is being executed. 

# Resetting Simulation

So, you have been working on your simulation for a while now and the BlueSky main mapview has become too crowded with all the aircraft and you wish there was a way to just clear the mapview. Luckily there is a command for that as well! Type 

    RESET 

in the command line to reset your simulation without closing the BlueSky. 

# Setting Simulation Clock Time

It is also possible to change the simulated clock time **UTC** in the Simulation State panel. Initially, the simulated clock time starts at 00:00:00 just like the simulation time. 
To change it, type TIME in the command line followed by your choice of time. There are 5 options. 

Type in TIME followed by the values for hours, minutes and seconds in the HH:MM:SS format to set a specific time or TIME followed by the value for hours to set a specific hour. 

Furthermore, you could type in 

    TIME REAL 

to set it equal to the time on your computer or 

    TIME UTC 

to set it equal to UTC time. 

![TimeUTC](images/time_utc.png)

It is also possible to set the simulated clock time back to be equal to the simulation time by typing in 

    TIME RUN

# Quitting Simulation

When you have finished your simulation and would like to close the BlueSky, you could type 

    QUIT 

in the command line or simply close the BlueSky window. 

**Note**: It might be safer to use the QUIT command since some commands you have typed in before might run until the BlueSky is stopped. Command QUIT provides a safe environment for these commands to complete properly.

Next tutorial: [[Scenario Files]]