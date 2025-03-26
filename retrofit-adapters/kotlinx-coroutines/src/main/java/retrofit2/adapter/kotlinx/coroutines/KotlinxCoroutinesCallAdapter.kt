package retrofit2.adapter.kotlinx.coroutines

import java.lang.reflect.Type
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import retrofit2.Call
import retrofit2.CallAdapter

class KotlinxCoroutinesCallAdapter<T : Any>(private val responseType: Type) : CallAdapter<T, Flow<T>> {
  override fun responseType(): Type = responseType

  override fun adapt(call: Call<T?>): Flow<T> = flow {
    val response = call.execute()
    if (response.isSuccessful) {
      val body = response.body()
      if (body != null) {
        emit(body)
      }
    }
  }
}
