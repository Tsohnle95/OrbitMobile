import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers

/**
 * Plain HTTP fetches that bypass ContentNegotiation. The v2 fs/read endpoint
 * returns raw file text but labels JSON/YAML files with their real content
 * type, which breaks Ktor's String decoding.
 */
class RawFetcher(private val client: HttpClient) {
    suspend fun getText(url: String): Pair<Int, String> = Dispatchers.IO.run {
        val resp = client.get(url)
        val text = resp.bodyAsText()
        resp.status.value to text
    }
}
