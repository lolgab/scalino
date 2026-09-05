package domain

import upickle.default.ReadWriter

/**
 * Type class per l'etichetta leggibile di un valore enum in UI (es. "Dal 1901 al 1920" invece di
 * `Dal1901Al1920`) — il nome del case resta invariato perché usato come valore di form/serializzazione, la
 * label è solo per la visualizzazione.
 */
trait Label[E]:
  def of(e: E): String

enum Esposizione derives ReadWriter:
  case Nord, Sud, Est, Ovest, NordEst, NordOvest, SudEst, SudOvest

object Esposizione:
  given Label[Esposizione] with
    def of(e: Esposizione): String = e match
      case Nord => "Nord"
      case Sud => "Sud"
      case Est => "Est"
      case Ovest => "Ovest"
      case NordEst => "Nord-Est"
      case NordOvest => "Nord-Ovest"
      case SudEst => "Sud-Est"
      case SudOvest => "Sud-Ovest"

enum ContestoUrbano derives ReadWriter:
  case CentroCitta, Periferia, Isolato

object ContestoUrbano:
  given Label[ContestoUrbano] with
    def of(e: ContestoUrbano): String = e match
      case CentroCitta => "Centro città"
      case Periferia => "Periferia"
      case Isolato => "Isolato"

enum ColoreFinitura derives ReadWriter:
  case Chiaro, Medio, Scuro

object ColoreFinitura:
  given Label[ColoreFinitura] with
    def of(e: ColoreFinitura): String = e match
      case Chiaro => "Chiaro"
      case Medio => "Medio"
      case Scuro => "Scuro"

enum TipoGenerazione derives ReadWriter:
  case CaldaiaStandard, CaldaiaCondensazione, PompaDiCalore, Biomassa

object TipoGenerazione:
  given Label[TipoGenerazione] with
    def of(e: TipoGenerazione): String = e match
      case CaldaiaStandard => "Caldaia standard"
      case CaldaiaCondensazione => "Caldaia a condensazione"
      case PompaDiCalore => "Pompa di calore"
      case Biomassa => "Biomassa"

enum TipoEmissione derives ReadWriter:
  case Radiatori, PannelliRadianti, FanCoil, AriaCalda

object TipoEmissione:
  given Label[TipoEmissione] with
    def of(e: TipoEmissione): String = e match
      case Radiatori => "Radiatori"
      case PannelliRadianti => "Pannelli radianti"
      case FanCoil => "Fan coil"
      case AriaCalda => "Aria calda"

enum TipoRegolazione derives ReadWriter:
  case OnOff, Climatica, ZonaSingola, ZonaMultipla

object TipoRegolazione:
  given Label[TipoRegolazione] with
    def of(e: TipoRegolazione): String = e match
      case OnOff => "On/Off"
      case Climatica => "Climatica"
      case ZonaSingola => "Zona singola"
      case ZonaMultipla => "Zona multipla"

/** Fascia di epoca costruttiva, TABULA/EPISCOPE Italia (chiave verso EnvelopeCatalog). */
enum EpocaCostruttiva derives ReadWriter:
  case Fino1900, Dal1901Al1920, Dal1921Al1945, Dal1946Al1960, Dal1961Al1975, Dal1976Al1990,
    Dal1991Al2005, Dopo2005

object EpocaCostruttiva:
  given Label[EpocaCostruttiva] with
    def of(e: EpocaCostruttiva): String = e match
      case Fino1900 => "Fino al 1900"
      case Dal1901Al1920 => "Dal 1901 al 1920"
      case Dal1921Al1945 => "Dal 1921 al 1945"
      case Dal1946Al1960 => "Dal 1946 al 1960"
      case Dal1961Al1975 => "Dal 1961 al 1975"
      case Dal1976Al1990 => "Dal 1976 al 1990"
      case Dal1991Al2005 => "Dal 1991 al 2005"
      case Dopo2005 => "Dopo il 2005"

/**
 * Epoca dell'impianto (chiave verso SystemCatalog per l'efficienza di generazione, UNI/TS 11300-2 prospetti
 * 23/23d) — vocabolario indipendente da EpocaCostruttiva perché un impianto può essere stato rifatto in un
 * momento diverso dalla costruzione dell'edificio.
 */
enum EpocaImpianto derives ReadWriter:
  case PrimaDel1995, Dal1996Al2008, Dal2009

object EpocaImpianto:
  given Label[EpocaImpianto] with
    def of(e: EpocaImpianto): String = e match
      case PrimaDel1995 => "Prima del 1995"
      case Dal1996Al2008 => "Dal 1996 al 2008"
      case Dal2009 => "Dal 2009"

/**
 * Classe di inerzia termica dell'edificio, da cui deriva la costante di tempo termica τ (ore) usata nel
 * fattore di utilizzazione dei guadagni η_H,gn (UNI/TS 11300-1, EN ISO 13790). Valori di default per classe,
 * non calcolati dalla capacità termica reale della struttura (semplificazione v1 rispetto al calcolo puntuale
 * di C_m).
 */
enum InerziaTermica(val tauOre: Double) derives ReadWriter:
  case Leggera extends InerziaTermica(8.0)
  case Media extends InerziaTermica(15.0)
  case Pesante extends InerziaTermica(30.0)

object InerziaTermica:
  given Label[InerziaTermica] with
    def of(e: InerziaTermica): String = e match
      case Leggera => "Leggera"
      case Media => "Media"
      case Pesante => "Pesante"

case class Involucro(
    epocaCostruttiva: EpocaCostruttiva,
    coloreFinitura: ColoreFinitura,
    inerziaTermica: InerziaTermica,
    areaPareteM2: Double,
    areaCoperturaM2: Double,
    areaBasamentoM2: Double,
    ambienteAdiacenteParete: AmbienteAdiacenteParete = AmbienteAdiacenteParete.Nessuno,
    ambienteAdiacenteSoffitto: AmbienteAdiacenteSoffitto = AmbienteAdiacenteSoffitto.Nessuno,
    ambienteAdiacentePavimento: AmbienteAdiacentePavimento = AmbienteAdiacentePavimento.Nessuno,
) derives ReadWriter

case class Serramento(
    esposizione: Esposizione,
    areaM2: Double,
    tipoVetro: TipoVetro,
    tipoTelaio: TipoTelaio,
    schermatura: Boolean,
) derives ReadWriter

case class ImpiantoRiscaldamento(
    generazione: TipoGenerazione,
    emissione: TipoEmissione,
    regolazione: TipoRegolazione,
    epocaImpianto: EpocaImpianto,
) derives ReadWriter

case class ImpiantoAcs(
    generazione: TipoGenerazione,
    epocaImpianto: EpocaImpianto,
) derives ReadWriter

case class ImpiantoRaffrescamento(
    presente: Boolean,
    generazione: TipoGenerazione = TipoGenerazione.PompaDiCalore,
) derives ReadWriter

/**
 * Fonti rinnovabili in situ (solare termico per ACS, fotovoltaico), DOCET modulo "Rinnovabili" (Manuale
 * Utente DOCET §5.5). Campi opzionali, default zero = nessun impianto rinnovabile. Riducono rispettivamente
 * il fabbisogno ACS coperto da fonte non rinnovabile e l'energia elettrica consegnata da fonte non
 * rinnovabile, contribuendo a EPgl,ren — mai l'edificio di riferimento, che usa sempre la tecnologia standard
 * di Tabella 1 indipendentemente dagli impianti rinnovabili reali installati.
 */
case class Rinnovabili(
    areaSolareTermicoM2: Double = 0.0,
    potenzaFotovoltaicoKwp: Double = 0.0,
) derives ReadWriter

/**
 * Superficie utile massima per cui il metodo di calcolo semplificato DOCET/UNI-TS 11300 è legalmente
 * applicabile (DM 26/6/2015, Linee Guida APE, Allegato 1 §4.2.2). Oltre questa soglia serve il metodo di
 * progetto pieno (`MetodoCalcolo.Progetto`).
 */
val SUPERFICIE_MASSIMA_METODO_SEMPLIFICATO_M2: Double = 200.0

/**
 * Metodo di calcolo UNI/TS 11300 scelto per l'attestato — il semplificato (stile DOCET, usato finora in
 * questo progetto: ponti termici a maggiorazione percentuale, ricambio d'aria fisso, irraggiamento per
 * fattore di orientamento approssimato) resta soggetto al limite dei 200 m² (Allegato 1 §4.2.2); il metodo di
 * progetto pieno non ha questo limite ma richiede un motore di calcolo distinto (ponti termici da abaco,
 * ricambio d'aria da permeabilità reale, irraggiamento da tabelle UNI 10349 per orientamento, multi-zona) —
 * costruito incrementalmente, componente sorgente-verificata per componente sorgente- verificata, non
 * implementato tutto insieme. Finché un dato componente del metodo di progetto non è pronto,
 * `PrimaryEnergy.calcola` rifiuta esplicitamente la combinazione invece di calcolare in silenzio con la
 * fisica del semplificato sotto un'etichetta "progetto" — stesso principio già seguito per i gap di catalogo
 * (vedi WindowCatalogData).
 */
enum MetodoCalcolo derives ReadWriter:
  case Semplificato, Progetto

object MetodoCalcolo:
  given Label[MetodoCalcolo] with
    def of(e: MetodoCalcolo): String = e match
      case Semplificato => "Semplificato (DOCET, superficie utile <= 200 m²)"
      case Progetto => "Di progetto (nessun limite di superficie)"

/**
 * Riferimenti catastali dell'immobile — sezione "Dati identificativi" dell'Allegato 1. Facoltativi: un vero
 * APE li richiede, ma qui non bloccano il calcolo (li compila/ verifica il certificatore che poi firma
 * l'attestato).
 */
case class DatiCatastali(
    foglio: String = "",
    particella: String = "",
    subalterno: String = "",
) derives ReadWriter

/**
 * Motivo di emissione dell'APE, Allegato 1 §3 — voce anagrafica obbligatoria in un vero attestato, non usata
 * nel calcolo.
 */
enum MotivoEmissione derives ReadWriter:
  case NuovaCostruzione, CompravenditaTrasferimento, NuovoContrattoLocazione,
    RistrutturazioneImportante, AttestazioneVolontaria

object MotivoEmissione:
  given Label[MotivoEmissione] with
    def of(e: MotivoEmissione): String = e match
      case NuovaCostruzione => "Nuova costruzione"
      case CompravenditaTrasferimento => "Compravendita / trasferimento a titolo oneroso"
      case NuovoContrattoLocazione => "Nuovo contratto di locazione"
      case RistrutturazioneImportante => "Ristrutturazione importante"
      case AttestazioneVolontaria => "Attestazione volontaria"

/**
 * Anagrafica APE — immobile, soggetti e motivo di emissione (Allegato 1 §3): dati che un vero attestato deve
 * riportare in frontespizio ma che qui restano facoltativi e non influenzano il calcolo energetico, coerente
 * con [[MotivoEmissione]]/[[DatiCatastali]]. Il certificatore che firma l'attestato è responsabile della loro
 * correttezza, non questo software (vedi disclaimer in `pdf.ApeDocument`).
 */
case class Anagrafica(
    indirizzo: String = "",
    provincia: String = "",
    cap: String = "",
    datiCatastali: DatiCatastali = DatiCatastali(),
    motivoEmissione: MotivoEmissione = MotivoEmissione.AttestazioneVolontaria,
    proprietarioNome: String = "",
    certificatoreNome: String = "",
    certificatoreCodiceFiscale: String = "",
    certificatoreNumeroIscrizione: String = "",
    dataSopralluogo: String = "",
    dataEmissione: String = "",
) derives ReadWriter

case class BuildingInput(
    nomeProgetto: String,
    comune: String,
    superficieUtileM2: Double,
    volumeLordoM3: Double,
    contestoUrbano: ContestoUrbano,
    involucro: Involucro,
    serramenti: List[Serramento],
    riscaldamento: ImpiantoRiscaldamento,
    acs: ImpiantoAcs,
    raffrescamento: ImpiantoRaffrescamento,
    rinnovabili: Rinnovabili = Rinnovabili(),
    anagrafica: Anagrafica = Anagrafica(),
    metodoCalcolo: MetodoCalcolo = MetodoCalcolo.Semplificato,
) derives ReadWriter

enum BuildingInputError:
  case SuperficieOltreLimiteMetodoSemplificato(valoreM2: Double)
  case SuperficieNonPositiva
  case VolumeNonPositivo
  case NessunSerramento
  case NomeProgettoVuoto
  case AreaInvolucroNonPositiva(elemento: String)
  case AreaSerramentoNonPositiva(indice: Int)

  def messaggio: String = this match
    case SuperficieOltreLimiteMetodoSemplificato(v) =>
      f"Superficie utile di $v%.1f m² oltre il limite di $SUPERFICIE_MASSIMA_METODO_SEMPLIFICATO_M2%.0f m² " +
        "per cui il metodo di calcolo semplificato è applicabile (DM 26/6/2015, Linee Guida APE §4.2.2) " +
        "— seleziona il metodo di progetto."
    case SuperficieNonPositiva => "La superficie utile deve essere maggiore di zero."
    case VolumeNonPositivo => "Il volume lordo riscaldato deve essere maggiore di zero."
    case NessunSerramento => "È necessario specificare almeno un serramento."
    case NomeProgettoVuoto => "Il nome del progetto è obbligatorio."
    case AreaInvolucroNonPositiva(elemento) => s"La superficie di $elemento deve essere maggiore di zero."
    case AreaSerramentoNonPositiva(i) =>
      s"La superficie del serramento ${i + 1} deve essere maggiore di zero."

object BuildingInput:
  def validate(b: BuildingInput): List[BuildingInputError] =
    List(
      Option.when(
        b.metodoCalcolo == MetodoCalcolo.Semplificato &&
          b.superficieUtileM2 > SUPERFICIE_MASSIMA_METODO_SEMPLIFICATO_M2,
      )(BuildingInputError.SuperficieOltreLimiteMetodoSemplificato(b.superficieUtileM2)),
      Option.when(b.superficieUtileM2 <= 0)(BuildingInputError.SuperficieNonPositiva),
      Option.when(b.volumeLordoM3 <= 0)(BuildingInputError.VolumeNonPositivo),
      Option.when(b.serramenti.isEmpty)(BuildingInputError.NessunSerramento),
      Option.when(b.nomeProgetto.isBlank)(BuildingInputError.NomeProgettoVuoto),
      Option.when(
        b.involucro.areaPareteM2 <= 0,
      )(BuildingInputError.AreaInvolucroNonPositiva("parete esterna")),
      Option.when(b.involucro.areaCoperturaM2 <= 0)(BuildingInputError.AreaInvolucroNonPositiva("copertura")),
      Option.when(b.involucro.areaBasamentoM2 <= 0)(
        BuildingInputError.AreaInvolucroNonPositiva("basamento/contro terra"),
      ),
    ).flatten ++ b.serramenti.zipWithIndex.collect {
      case (s, i) if s.areaM2 <= 0 => BuildingInputError.AreaSerramentoNonPositiva(i)
    }
