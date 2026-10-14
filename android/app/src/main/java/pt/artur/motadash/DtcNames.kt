package pt.artur.motadash

/**
 * Plain-language names for the generic (SAE, P0xxx) fault codes a small
 * injected single-cylinder ECU can raise. Manufacturer codes (P1xxx and
 * others) are shown without a name.
 */
object DtcNames {
    private val names = mapOf(
        "P0030" to "Aquecedor da sonda lambda (sensor 1): circuito",
        "P0031" to "Aquecedor da sonda lambda (sensor 1): tensão baixa",
        "P0032" to "Aquecedor da sonda lambda (sensor 1): tensão alta",
        "P0105" to "Sensor de pressão de admissão (MAP): circuito",
        "P0106" to "Sensor MAP: fora da gama / desempenho",
        "P0107" to "Sensor MAP: tensão baixa",
        "P0108" to "Sensor MAP: tensão alta",
        "P0110" to "Sensor de temperatura do ar (IAT): circuito",
        "P0112" to "Sensor IAT: tensão baixa",
        "P0113" to "Sensor IAT: tensão alta",
        "P0115" to "Sensor de temperatura do motor: circuito",
        "P0116" to "Sensor de temperatura do motor: fora da gama",
        "P0117" to "Sensor de temperatura do motor: tensão baixa",
        "P0118" to "Sensor de temperatura do motor: tensão alta",
        "P0120" to "Sensor de posição do acelerador (TPS): circuito",
        "P0121" to "Sensor TPS: fora da gama / desempenho",
        "P0122" to "Sensor TPS: tensão baixa",
        "P0123" to "Sensor TPS: tensão alta",
        "P0130" to "Sonda lambda (sensor 1): circuito",
        "P0131" to "Sonda lambda (sensor 1): tensão baixa",
        "P0132" to "Sonda lambda (sensor 1): tensão alta",
        "P0133" to "Sonda lambda (sensor 1): resposta lenta",
        "P0134" to "Sonda lambda (sensor 1): sem atividade",
        "P0135" to "Aquecedor da sonda lambda (sensor 1): circuito",
        "P0171" to "Mistura demasiado pobre",
        "P0172" to "Mistura demasiado rica",
        "P0201" to "Injetor (cilindro 1): circuito",
        "P0230" to "Bomba de combustível: circuito primário",
        "P0261" to "Injetor (cilindro 1): circuito em baixo",
        "P0262" to "Injetor (cilindro 1): circuito em alto",
        "P0300" to "Falhas de ignição detetadas",
        "P0301" to "Falhas de ignição no cilindro 1",
        "P0335" to "Sensor de posição da cambota (CKP): circuito",
        "P0336" to "Sensor CKP: fora da gama / desempenho",
        "P0351" to "Bobina de ignição A: circuito",
        "P0500" to "Sensor de velocidade do veículo",
        "P0505" to "Controlo do ralenti: sistema",
        "P0506" to "Ralenti abaixo do esperado",
        "P0507" to "Ralenti acima do esperado",
        "P0508" to "Controlo do ralenti: tensão baixa",
        "P0509" to "Controlo do ralenti: tensão alta",
        "P0511" to "Controlo do ralenti: circuito",
        "P0560" to "Tensão do sistema (bateria)",
        "P0562" to "Tensão do sistema baixa",
        "P0563" to "Tensão do sistema alta",
        "P0601" to "Centralina: erro de memória",
        "P0602" to "Centralina: erro de programação",
        "P0627" to "Bomba de combustível: circuito de comando",
        "P0628" to "Bomba de combustível: comando em baixo",
        "P0629" to "Bomba de combustível: comando em alto",
        "P0650" to "Luz de avaria (MIL): circuito",
        "P0657" to "Alimentação de atuadores: circuito",
        "P0850" to "Interruptor de ponto-morto: circuito",
    )

    fun name(code: String): String? = names[code] ?: when {
        code.length == 5 && code[1] != '0' -> "Código específico do fabricante"
        else -> null
    }
}
