package domain

import upickle.default.ReadWriter

/**
 * Ambiente non riscaldato adiacente a un elemento dell'involucro opaco: riduce lo scambio termico verso
 * l'esterno tramite il fattore btr,U (UNI/TS 11300-1 §11.2, prospetto 7 — valori semplificati "per edifici
 * esistenti"; fonte primaria consultata: manuale ANIT "Isolamento di strutture verso locali non riscaldati",
 * che riporta esplicitamente lo stesso prospetto con citazione). Nessuna riduzione (btr=1.0) quando
 * l'elemento confina direttamente con l'esterno/terreno — default, comportamento invariato rispetto a prima
 * di questa funzionalità.
 *
 * Tre enum separati (invece di uno condiviso) perché non tutte le righe del prospetto 7 hanno senso per ogni
 * elemento (es. "sottotetto" non si applica a una parete verticale) — rispecchia le opzioni distinte che
 * DOCET offre per ciascun elemento (Manuale Utente DOCET §4.4: pavimento/soffitto/pareti confinanti con
 * ambiente non riscaldato hanno ciascuno il proprio elenco).
 *
 * Nota su cosa NON è ancora coperto: DOCET permette anche di dichiarare che un solaio confina per il 50% con
 * il terreno/esterno e per il 50% con la cantina/sottotetto (superficie mista) — qui non implementato:
 * l'utente sceglie una sola categoria per l'intera superficie. Anche `AltroAmbienteNonRiscaldato` (pareti) è
 * una scelta generica di fallback per un caso non altrimenti specificato, non un valore verificato in una
 * fonte specifica per quel caso — le altre voci sono invece lette direttamente dal prospetto 7.
 */

enum AmbienteAdiacentePavimento derives ReadWriter:
  case Nessuno, CantinaGarageSenzaFinestre, CantinaGarageConFinestre

object AmbienteAdiacentePavimento:
  def btr(a: AmbienteAdiacentePavimento): Double = a match
    case Nessuno => 1.0
    case CantinaGarageSenzaFinestre => 0.5
    case CantinaGarageConFinestre => 0.8

  given Label[AmbienteAdiacentePavimento] with
    def of(a: AmbienteAdiacentePavimento): String = a match
      case Nessuno => "Nessuno (esterno o terreno diretto)"
      case CantinaGarageSenzaFinestre => "Cantina o garage, senza finestre/serramenti esterni"
      case CantinaGarageConFinestre => "Cantina o garage, con finestre/serramenti esterni"

enum AmbienteAdiacenteSoffitto derives ReadWriter:
  case Nessuno, SottotettoVentilazioneElevata, SottotettoNonIsolato, SottotettoIsolato

object AmbienteAdiacenteSoffitto:
  def btr(a: AmbienteAdiacenteSoffitto): Double = a match
    case Nessuno => 1.0
    case SottotettoVentilazioneElevata => 1.0
    case SottotettoNonIsolato => 0.9
    case SottotettoIsolato => 0.7

  given Label[AmbienteAdiacenteSoffitto] with
    def of(a: AmbienteAdiacenteSoffitto): String = a match
      case Nessuno => "Nessuno (esterno diretto)"
      case SottotettoVentilazioneElevata =>
        "Sottotetto con tetto molto ventilato (es. tegole su copertura discontinua)"
      case SottotettoNonIsolato => "Sottotetto con tetto non isolato"
      case SottotettoIsolato => "Sottotetto con tetto isolato"

enum AmbienteAdiacenteParete derives ReadWriter:
  case Nessuno, VanoScalaInternoUnAffaccio, VanoScalaInternoNessunAffaccio, VanoScalaEsterno,
    AltroAmbienteNonRiscaldato

object AmbienteAdiacenteParete:
  def btr(a: AmbienteAdiacenteParete): Double = a match
    case Nessuno => 1.0
    case VanoScalaInternoUnAffaccio => 0.4
    case VanoScalaInternoNessunAffaccio => 0.0
    case VanoScalaEsterno => 0.8
    case AltroAmbienteNonRiscaldato => 0.5

  given Label[AmbienteAdiacenteParete] with
    def of(a: AmbienteAdiacenteParete): String = a match
      case Nessuno => "Nessuno (esterno diretto)"
      case VanoScalaInternoUnAffaccio => "Vano scala interno, con un affaccio esterno"
      case VanoScalaInternoNessunAffaccio => "Vano scala interno, senza affacci esterni"
      case VanoScalaEsterno => "Vano scala esterno (con serramenti esterni)"
      case AltroAmbienteNonRiscaldato => "Altro ambiente non riscaldato generico"
