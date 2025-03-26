package retrofit2.adapter.kotlinx.coroutines

import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import kotlinx.coroutines.flow.Flow
import retrofit2.Call
import retrofit2.CallAdapter
import retrofit2.Retrofit

class KotlinxCoroutinesCallAdapterFactory private constructor(): CallAdapter.Factory() {
  override fun get(
    returnType: Type,
    annotations: Array<Annotation>,
    retrofit: Retrofit,
  ): CallAdapter<*, *>? {
    val rawReturnType = getRawType(returnType)
    // Suspend functions wrap the response type in `Call`.
    if (Call::class.java != rawReturnType && Flow::class.java != rawReturnType) {
      return null
    }

    check(returnType is ParameterizedType) {
      "return type must be Flow<Foo> or Flow<out Foo>"
    }

    val responseType = getParameterUpperBound(0, returnType)
    if (Flow::class.java == getRawType(responseType)) {
      return KotlinxCoroutinesCallAdapter<Any>(getParameterUpperBound(0, responseType as ParameterizedType))
    }

    return null
  }

  companion object {
    @JvmStatic
    fun create(): KotlinxCoroutinesCallAdapterFactory = KotlinxCoroutinesCallAdapterFactory()
  }
}
