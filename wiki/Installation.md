In this page:
1. [Install BlueSky from source](#Source-install)
2. [Install Python 3 (3.7 or higher)](#Install-Python-3)
3. [Install BlueSky as a pip package](#PIP-package-install)
4. [Troubleshooting](#Troubleshooting)

If you new to Python, please first go to [Install Python 3](#Install-Python-3) section first. 

# Source install
To install BlueSky from source, you can either clone the git repository, or download a zip bundle:

1. Clone the BlueSky source repository:

        git clone https://github.com/TUDelft-CNS-ATM/bluesky.git

2. Download a zip file of the main branch:  

    https://github.com/TUDelft-CNS-ATM/bluesky/archive/master.zip

To install BlueSky's dependencies, run the following from the BlueSky root folder:

    pip install -e ".[full]"

Or if you only want to run headless, without a gui:

    pip install -e .

you can run BlueSky as follows, in the BlueSky root folder type:

    python3 BlueSky.py

# Install Python 3

You need Python 3.10 or preferably a more recent version of Python. 

There are three installation options for python (two for windows):
* Manual installation of python from [Python.org](https://www.python.org/downloads/), recommended for Windows and macOS. Select a recent version of Python. If the newest release is very fresh you might want to select the one before that, as it sometimes takes a while for all packages to be available through pip.
* Installation using the native package manager of the OS (Linux) or a 3rd-party package manager (macOS: macPorts, homebrew)
* Installation of a python application bundle such as Anaconda or Miniconda (all platforms) is also possible, but it can sometimes happen that packages installed with e.g. conda have issues (with BlueSky).



## Windows-specific installation steps

Install Python from [Python.org](https://www.python.org/downloads/). In the Windows installer the PATH to be added.  Python will also install the python package manager pip. To install the modules use the `pip install 'modulename'` in a Console with sufficient rights (**administrator**). Then open a Console (or in Windows Run a Command Prompt as Administrator) and run the following command:

    pip install -e .[full]

In Microsoft Windows you can also right-click and select "Run as Administrator" for the supplied file: 

    setup-python.bat


## macOS 
It is not recommended to use the default Python distribution that comes with the Mac OS for BlueSky.

The preferred installation method for Python on macOS is to install Python from [Python.org](https://www.python.org/downloads/). Python will also install the python package manager pip. To install the modules use the `pip install` command in the terminal. Go to the folder where you installed BlueSky, and run the following:

    pip install -e ".[full]"

You can also use the Anaconda or Miniconda Python distribution (see below), or install Python using macports or homebrew.


## Linux
Each Linux distribution has its own package management software. You can use it to install the required dependencies for BlueSky. Make sure to have all the dependency libraries installed.


## Anaconda or Miniconda (all OS)
Another way to start using the BlueSky source code is to install a Python distribution such as [Anaconda](https://www.continuum.io/downloads) or [Miniconda](https://docs.conda.io/en/latest/miniconda.html). Make sure the Python 3 version of these distributions is installed. Miniconda is a stripped-down version of the Anaconda, where more libraries need to be installed manually. 

### Anaconda
After installation, open Anaconda Prompt (or shell console) and run following command to install additional libraries:

     pip install pyopengl pygame pyqtwebengine

### Miniconda
After installation, open the Prompt (or shell console) and run following commands to install additional libraries:

    conda install pyqt numpy scipy matplotlib pandas pyopengl rtree
    pip install msgpack pyzmq pygame pyqtwebengine zmq


# PIP package install
If you are not (yet) planning to edit the BlueSky source files, the easiest way to get BlueSky is installing it from PyPI. In a terminal / command prompt, execute the following command:

    pip install bluesky-simulator[full]

If you are on macOS or Linux, and use a zsh terminal, you have to put quotes around the package name:

    pip install "bluesky-simulator[full]"

You can now run BlueSky with one of the following two commands in the terminal/command line:

    bluesky

or

    python3 -m bluesky

Note that `[full]` indicates that all BlueSky gui dependencies should be installed. Other installation options are:
- `[qt5]` Install BlueSky with Qt5 dependencies
- `[qt6]` Install BlueSky with Qt6 dependencies
- `[console]` Install BlueSky with (only) textual console dependencies
- `[headless]` Don't install any gui dependencies


# Troubleshooting

##  'xcb' plugin for OpenGL could not be found

This is a specific error with Anaconda on some Linux. Running BlueSky gives you an error saying that the 'xcb' plugin for OpenGL could not be found. You can fix this by adding the following to your console profile file (either `~/.bashrc`, `~/.profile`, or `~/.bash_profile`):

    export QT_PLUGIN_PATH=/usr/lib/x86_64-linux-gnu/qt5/plugins/platforms/

Possibly, your conda files also need updating, which can be performed by following [these](http://docs.glueviz.org/en/stable/known_issues.html#d-viewers-not-working-on-linux-with-pyqt5) instructions.
