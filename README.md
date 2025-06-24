# Logger

## Description
Logger is a mobile application developed in Kotlin that collects and logs sensor data from a device, including yaw, pitch, roll, latitude, longitude, altitude, and vehicle CAN data.
The collected data is saved in a CSV format for research and analysis purposes.

## Features
- Collects yaw, pitch, and roll sensor data with alighned IMU timestamp.
- Captures device's current latitude, longitude.
- Records Vehicle CAN data and update in the csv.
- Saves all collected data into a CSV file.
- Easy-to-use interface for starting/stopping data collection.

## Requirements
- Android Studio
- Android Device/Emulator with sensor capabilities 

## Installation

1. **Clone the repository**: 
   Open your terminal and run the following command to clone the repository:
   ```bash 
   git clone https://github.com/Ashutosh-AE/Logger.git

   
Open the Project in Android Studio:
-> Launch Android Studio and select Open an existing Android Studio project.
-> Navigate to the directory where you cloned the repository and select it.

Build the Project:
-> Android Studio will automatically start syncing the Gradle files or manually sync.
-> After synchronization, click on the Run button to build and run the app on an emulator or connected device.

Usage
1) Open the Logger app on your device.
2) Start the data collection by pressing the "Start" button.
3) 
The app will continuously log data and save it in CSV format.
Press "Stop" to end the data collection.
Access the generated CSV file from the device storage.

Contributing
Contributions are welcome! If you have suggestions or improvements, feel free to fork the repository and submit pull requests.

Contact
For questions or further assistance, reach out to ashutosh.tripathy@atherenergy.com
