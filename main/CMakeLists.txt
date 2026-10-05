idf_component_register(
    SRCS "main.c" "obd_poller.c"
    INCLUDE_DIRS "."
    REQUIRES bt nvs_flash esp_driver_twai esp_lcd esp_driver_spi esp_driver_gpio
)
