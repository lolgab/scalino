package domain

import upickle.default.ReadWriter

enum TipoVetro derives ReadWriter:
  case Singolo, Doppio, DoppioBassoEmissivo, Triplo

object TipoVetro:
  given Label[TipoVetro] with
    def of(e: TipoVetro): String = e match
      case Singolo => "Singolo"
      case Doppio => "Doppio"
      case DoppioBassoEmissivo => "Doppio basso-emissivo"
      case Triplo => "Triplo"

enum TipoTelaio derives ReadWriter:
  case Legno, Pvc, MetalloSenzaTagliotermico, MetalloConTaglioTermico

object TipoTelaio:
  given Label[TipoTelaio] with
    def of(e: TipoTelaio): String = e match
      case Legno => "Legno"
      case Pvc => "PVC"
      case MetalloSenzaTagliotermico => "Metallo senza taglio termico"
      case MetalloConTaglioTermico => "Metallo con taglio termico"

/**
 * Trasmittanza (Uw, W/m²K) e fattore solare (g) tipici per tipo vetro/telaio. Fonte: UNI/TS 11300-1:2014
 * Appendice B, valori ripresi da letteratura tecnica secondaria (norma originale a pagamento) — vedi
 * CATALOG_SOURCES.md.
 */
case class ProprietaSerramento(uw: Double, g: Double)
