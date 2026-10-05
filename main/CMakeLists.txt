idf_component_register(
    SRCS "main.c"
         "can_bus_recovery_fsm.c"
         "can_bus_supervisor.c"
         "can_bus_twai.c"
         "obd_poller.c"
         "fuel_estimator.c"
         "boot_diag.c"
    INCLUDE_DIRS "."
    REQUIRES bt nvs_flash esp_driver_twai esp_lcd esp_driver_spi esp_driver_gpio esp_timer
)
