package com.tattoostencil.pro.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.microsoft.onnxruntime.OnnxTensor
import com.microsoft.onnxruntime.OrtEnvironment
import com.microsoft.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * StencilPro image engine.
 *
 * The previous implementation was a pure edge detector. This engine adds a learned
 * image-to-line stage (ONNX) and then performs stencil-specific cleanup/reconstruction.
 * The ONNX model is downloaded once to app-private storage from the Apache-2.0 model
 * repository cited in MODEL_NOTES.md; no photo is uploaded by this engine.
 */
object ImageProcessor {
    enum class FilterType(val displayName: String, val description: String) {
        AI_STENCIL("IA · Stencil", "Modelo de lineart + limpieza especializada para tatuaje"),
        CLEAN_LINES("Líneas limpias", "Contornos estructurales con limpieza fuerte"),
        FINE_DETAIL("Detalle fino", "Más líneas internas, con limpieza moderada"),
        THRESHOLD("Umbral", "Guía binaria"),
        SKETCH("Sketch", "Boceto de referencia"),
        HIGH_CONTRAST("Alto contraste", "Guía de alto contraste"),
        INVERT("Invertido", "Invierte el resultado")
    }

    enum class LineColor(val displayName: String, val color: Int) {
        PURPLE("Morado", Color.rgb(115, 35, 180)),
        BLACK("Negro", Color.BLACK),
        BLUE("Azul", Color.rgb(30, 90, 210)),
        RED("Rojo", Color.rgb(190, 35, 45))
    }

    data class ProcessingSettings(
        val brightness: Float = 0f,
        val contrast: Float = 1.08f,
        val threshold: Int = 92,
        val blurRadius: Int = 2,
        val edgeStrength: Int = 62,
        val invertColors: Boolean = false,
        val smoothEdges: Boolean = true,
        val lineWidth: Int = 1,
        val removeNoise: Boolean = true,
        val preserveDetail: Boolean = true,
        val backgroundWhite: Boolean = true,
        val lineColor: LineColor = LineColor.PURPLE,
        val aiCleanup: Int = 72,
        val aiDetail: Int = 68,
        val aiInputSize: Int = 768,
        val maxOutputSize: Int = 3000
    )

    suspend fun processImage(
        context: Context,
        bitmap: Bitmap,
        filterType: FilterType,
        settings: ProcessingSettings
    ): Bitmap = withContext(Dispatchers.Default) {
        val outputSource = normalize(bitmap, settings.maxOutputSize)
        val analysis = normalize(outputSource, min(settings.maxOutputSize, 1800))
        val gray = toGrayscale(analysis)
        val mask = when (filterType) {
            FilterType.AI_STENCIL -> aiStencilMask(context, gray, settings)
            FilterType.CLEAN_LINES -> professionalStencilMask(gray, settings)
            FilterType.FINE_DETAIL -> fineDetailMask(gray, settings)
            FilterType.THRESHOLD -> thresholdMask(gray, settings.threshold)
            FilterType.SKETCH -> sketchMask(gray, settings)
            FilterType.HIGH_CONTRAST -> thresholdMask(gray, settings.threshold.coerceIn(1, 254))
            FilterType.INVERT -> invertMask(gray)
        }
        var finalMask = mask
        if (settings.removeNoise && filterType != FilterType.THRESHOLD && filterType != FilterType.HIGH_CONTRAST) {
            finalMask = cleanMask(finalMask, analysis.width, analysis.height, settings.aiCleanup)
            finalMask = removeSmallComponents(finalMask, analysis.width, analysis.height, settings.preserveDetail)
            if (settings.smoothEdges) finalMask = bridgeAndThin(finalMask, analysis.width, analysis.height)
        }
        if (settings.lineWidth > 1 && filterType != FilterType.THRESHOLD && filterType != FilterType.HIGH_CONTRAST) {
            finalMask = thicken(finalMask, analysis.width, analysis.height, settings.lineWidth - 1)
        }
        renderMask(finalMask, analysis.width, analysis.height, outputSource.width, outputSource.height, settings)
    }

    /** Compatibility entry point for callers/tests that do not need the learned model. */
    fun processImage(bitmap: Bitmap, filterType: FilterType, settings: ProcessingSettings): Bitmap {
        val outputSource = normalize(bitmap, settings.maxOutputSize)
        val analysis = normalize(outputSource, min(settings.maxOutputSize, 1800))
        val gray = toGrayscale(analysis)
        val mask = when (filterType) {
            FilterType.AI_STENCIL, FilterType.CLEAN_LINES -> professionalStencilMask(gray, settings)
            FilterType.FINE_DETAIL -> fineDetailMask(gray, settings)
            FilterType.THRESHOLD, FilterType.HIGH_CONTRAST -> thresholdMask(gray, settings.threshold)
            FilterType.SKETCH -> sketchMask(gray, settings)
            FilterType.INVERT -> invertMask(gray)
        }
        return renderMask(mask, analysis.width, analysis.height, outputSource.width, outputSource.height, settings)
    }

    private fun aiStencilMask(context: Context, bitmap: Bitmap, s: ProcessingSettings): BooleanArray {
        val model = AiLineartEngine.get(context)
        if (!model.isReady()) return professionalStencilMask(bitmap, s)
        return try {
            val raw = model.infer(bitmap, s.aiInputSize, enhance = true)
            val resized = resizeMask(raw.first, raw.second, bitmap.width, bitmap.height)
            // AI output is combined with a conservative structural pass. This prevents
            // the model from turning photographic micro-texture into tattoo marks.
            val structure = professionalStencilMask(bitmap, s.copy(edgeStrength = (s.edgeStrength + 8).coerceAtMost(90)))
            val out = BooleanArray(bitmap.width * bitmap.height)
            val radius = max(2, (100 - s.aiDetail) / 18)
            for (y in 1 until bitmap.height - 1) for (x in 1 until bitmap.width - 1) {
                val i = y * bitmap.width + x
                val ai = resized[i]
                val nearStructure = if (ai) hasNearby(structure, bitmap.width, bitmap.height, x, y, radius) else false
                // Higher detail admits more AI-only strokes; cleanup reduces isolated marks.
                out[i] = ai && (nearStructure || s.aiDetail >= 82)
            }
            out
        } catch (_: Throwable) {
            professionalStencilMask(bitmap, s)
        }
    }

    fun normalize(bitmap: Bitmap, maxSize: Int): Bitmap {
        if (bitmap.width <= maxSize && bitmap.height <= maxSize) return bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val scale = min(maxSize.toFloat() / bitmap.width, maxSize.toFloat() / bitmap.height)
        return Bitmap.createScaledBitmap(bitmap, max(1, (bitmap.width * scale).toInt()), max(1, (bitmap.height * scale).toInt()), true)
    }

    fun toGrayscale(bitmap: Bitmap): Bitmap {
        val w = bitmap.width; val h = bitmap.height
        val src = IntArray(w * h); bitmap.getPixels(src, 0, w, 0, 0, w, h)
        val dst = IntArray(src.size)
        for (i in src.indices) {
            val p = src[i]
            val g = (0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)).toInt().coerceIn(0, 255)
            dst[i] = Color.rgb(g, g, g)
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(dst, 0, w, 0, 0, w, h) }
    }

    fun adjustBrightnessContrast(bitmap: Bitmap, brightness: Float, contrast: Float): Bitmap {
        val w = bitmap.width; val h = bitmap.height
        val src = IntArray(w * h); bitmap.getPixels(src, 0, w, 0, 0, w, h)
        val dst = IntArray(src.size); val factor = contrast.coerceIn(0.1f, 3f)
        for (i in src.indices) {
            val v = ((Color.red(src[i]) - 128f) * factor + 128f + brightness).toInt().coerceIn(0, 255)
            dst[i] = Color.rgb(v, v, v)
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(dst, 0, w, 0, 0, w, h) }
    }

    fun gaussianBlur(bitmap: Bitmap, radius: Int): Bitmap {
        if (radius <= 0) return bitmap
        val w = bitmap.width; val h = bitmap.height
        val src = IntArray(w * h); bitmap.getPixels(src, 0, w, 0, 0, w, h)
        val tmp = IntArray(src.size); val dst = IntArray(src.size)
        val size = radius * 2 + 1; val kernel = IntArray(size); var sum = 0
        for (i in 0 until size) { val x = i - radius; kernel[i] = max(1, (1000 * kotlin.math.exp(-(x * x) / (2.0 * radius * radius))).toInt()); sum += kernel[i] }
        for (y in 0 until h) for (x in 0 until w) { var v = 0; for (k in -radius..radius) v += Color.red(src[y*w+(x+k).coerceIn(0,w-1)]) * kernel[k+radius]; tmp[y*w+x] = v/sum }
        for (y in 0 until h) for (x in 0 until w) { var v = 0; for (k in -radius..radius) v += tmp[(y+k).coerceIn(0,h-1)*w+x] * kernel[k+radius]; val q=v/sum; dst[y*w+x]=Color.rgb(q,q,q) }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(dst,0,w,0,0,w,h) }
    }

    private fun professionalStencilMask(bitmap: Bitmap, s: ProcessingSettings): BooleanArray {
        val radius = if (s.smoothEdges) max(2, s.blurRadius.coerceIn(2, 4)) else 1
        val coarse = cannyLikeMask(gaussianBlur(bitmap, radius + 1), (s.edgeStrength + 10).coerceIn(35, 95), false)
        val fine = cannyLikeMask(gaussianBlur(bitmap, radius), s.edgeStrength.coerceIn(30, 90), true)
        val w=bitmap.width; val h=bitmap.height; val out=BooleanArray(w*h)
        for (i in out.indices) if (coarse[i]) out[i]=true
        for (y in 2 until h-2) for (x in 2 until w-2) { val i=y*w+x; if (!fine[i]||out[i]) continue; if (hasNearby(coarse,w,h,x,y,2)) out[i]=true }
        return out
    }

    private fun fineDetailMask(bitmap: Bitmap, s: ProcessingSettings): BooleanArray {
        val r=if(s.smoothEdges) s.blurRadius.coerceIn(1,2) else 0
        return cannyLikeMask(if(r>0) gaussianBlur(bitmap,r) else bitmap,(s.edgeStrength-12).coerceIn(20,85),true)
    }

    private fun cannyLikeMask(bitmap: Bitmap, strength: Int, keepFine: Boolean): BooleanArray {
        val w=bitmap.width; val h=bitmap.height; val src=IntArray(w*h); bitmap.getPixels(src,0,w,0,0,w,h)
        val mag=FloatArray(w*h); val dir=FloatArray(w*h)
        var maxMag=1f
        for(y in 1 until h-1) for(x in 1 until w-1){
            val p0=Color.red(src[(y-1)*w+x-1]); val p1=Color.red(src[(y-1)*w+x]); val p2=Color.red(src[(y-1)*w+x+1])
            val p3=Color.red(src[y*w+x-1]); val p5=Color.red(src[y*w+x+1]); val p6=Color.red(src[(y+1)*w+x-1]); val p7=Color.red(src[(y+1)*w+x]); val p8=Color.red(src[(y+1)*w+x+1])
            val gx=-p0-2*p3-p6+p2+2*p5+p8; val gy=-p0-2*p1-p2+p6+2*p7+p8
            val m=sqrt(gx.toFloat()*gx+gy.toFloat()*gy); val i=y*w+x; mag[i]=m; dir[i]=kotlin.math.atan2(gy.toFloat(),gx.toFloat()); if(m>maxMag)maxMag=m
        }
        val low=(maxMag*(0.035f+(100-strength)*0.0007f)).coerceIn(8f,55f); val high=(low*1.8f).coerceAtLeast(18f)
        val thin=BooleanArray(w*h)
        for(y in 1 until h-1) for(x in 1 until w-1){ val i=y*w+x; val angle=(dir[i]*180f/Math.PI).toFloat().let{if(it<0)it+180 else it}; val (a,b)=when{angle<22.5||angle>=157.5->mag[i-1] to mag[i+1]; angle<67.5->mag[(y-1)*w+x+1] to mag[(y+1)*w+x-1]; angle<112.5->mag[(y-1)*w+x] to mag[(y+1)*w+x]; else->mag[(y-1)*w+x-1] to mag[(y+1)*w+x+1]}; if(mag[i]>=a&&mag[i]>=b&&mag[i]>=low) thin[i]=true }
        val out=BooleanArray(w*h); val q=IntArray(w*h); var head=0; var tail=0
        for(i in thin.indices) if(thin[i]&&mag[i]>=high){out[i]=true;q[tail++]=i}
        while(head<tail){ val i=q[head++]; val y=i/w; val x=i%w; for(dy in -1..1)for(dx in -1..1){if(dx==0&&dy==0)continue; val xx=x+dx;val yy=y+dy;if(xx<1||xx>=w-1||yy<1||yy>=h-1)continue;val j=yy*w+xx;if(thin[j]&&!out[j]){out[j]=true;q[tail++]=j}} }
        if(keepFine) for(i in thin.indices) if(thin[i]&&mag[i]>=high*0.72f) out[i]=true
        return out
    }

    private fun thresholdMask(bitmap: Bitmap, threshold: Int): BooleanArray { val w=bitmap.width;val h=bitmap.height;val p=IntArray(w*h);bitmap.getPixels(p,0,w,0,0,w,h);return BooleanArray(p.size){Color.red(p[it])<threshold} }
    private fun sketchMask(bitmap: Bitmap,s:ProcessingSettings)=cannyLikeMask(gaussianBlur(bitmap,1),s.edgeStrength.coerceIn(20,80),true)
    private fun invertMask(bitmap: Bitmap): BooleanArray { val w=bitmap.width;val h=bitmap.height;val p=IntArray(w*h);bitmap.getPixels(p,0,w,0,0,w,h);return BooleanArray(p.size){Color.red(p[it])>180} }

    private fun cleanMask(mask:BooleanArray,w:Int,h:Int,level:Int):BooleanArray{
        var cur=mask
        val passes=(1+level/35).coerceIn(1,4)
        repeat(passes){
            val next=cur.copyOf()
            for(y in 1 until h-1)for(x in 1 until w-1){ val i=y*w+x; var n=0;for(dy in -1..1)for(dx in -1..1)if(!(dx==0&&dy==0)&&cur[(y+dy)*w+x+dx])n++; if(cur[i]&&n<=1)next[i]=false; if(!cur[i]&&n>=6)next[i]=true }
            cur=next
        }
        return cur
    }

    private fun removeSmallComponents(mask:BooleanArray,w:Int,h:Int,preserve:Boolean):BooleanArray{
        val out=mask.copyOf(); val seen=BooleanArray(mask.size); val stack=IntArray(mask.size); val minSize=if(preserve) max(10,(w*h)/18000) else max(20,(w*h)/9000)
        for(start in mask.indices){if(!mask[start]||seen[start])continue;var sp=0;var count=0;seen[start]=true;stack[sp++]=start
            while(sp>0){val i=stack[--sp];count++;val y=i/w;val x=i%w;for(dy in -1..1)for(dx in -1..1){if(dx==0&&dy==0)continue;val xx=x+dx;val yy=y+dy;if(xx<0||xx>=w||yy<0||yy>=h)continue;val j=yy*w+xx;if(mask[j]&&!seen[j]){seen[j]=true;stack[sp++]=j}}}
            if(count<minSize){sp=0;seen[start]=false;stack[sp++]=start;out[start]=false;val queue=IntArray(count.coerceAtLeast(1)); var qh=0;var qt=0;queue[qt++]=start;val local=HashSet<Int>();local.add(start)
                while(qh<qt){val i=queue[qh++];val y=i/w;val x=i%w;out[i]=false;for(dy in -1..1)for(dx in -1..1){val xx=x+dx;val yy=y+dy;if(xx<0||xx>=w||yy<0||yy>=h)continue;val j=yy*w+xx;if(mask[j]&&local.add(j)&&qt<queue.size)queue[qt++]=j}}
            }
        }
        return out
    }

    private fun bridgeAndThin(mask:BooleanArray,w:Int,h:Int):BooleanArray{
        var cur=mask
        val bridged=cur.copyOf()
        for(y in 1 until h-1)for(x in 1 until w-1){val i=y*w+x;if(cur[i])continue;var a=false;var b=false;for(d in 1..2){a=a||cur[y*w+(x-d).coerceAtLeast(0)]||cur[y*w+(x+d).coerceAtMost(w-1)];b=b||cur[(y-d).coerceAtLeast(0)*w+x]||cur[(y+d).coerceAtMost(h-1)*w+x]};if(a&&b)bridged[i]=true}
        cur=bridged
        return cur
    }

    private fun thicken(mask:BooleanArray,w:Int,h:Int,r:Int):BooleanArray{var cur=mask;repeat(r.coerceIn(0,3)){val n=BooleanArray(cur.size);for(y in 0 until h)for(x in 0 until w){val i=y*w+x;if(cur[i]){for(dy in -1..1)for(dx in -1..1){val xx=x+dx;val yy=y+dy;if(xx in 0 until w&&yy in 0 until h)n[yy*w+xx]=true}}};cur=n};return cur}

    private fun renderMask(mask:BooleanArray,w:Int,h:Int,outW:Int,outH:Int,s:ProcessingSettings):Bitmap{
        val pixels=IntArray(w*h);val line=s.lineColor.color;for(i in pixels.indices)pixels[i]=if(mask[i] xor s.invertColors)line else Color.WHITE
        val b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);b.setPixels(pixels,0,w,0,0,w,h)
        return if(w==outW&&h==outH)b else Bitmap.createScaledBitmap(b,outW,outH,true).also{b.recycle()}
    }

    private fun hasNearby(mask:BooleanArray,w:Int,h:Int,x:Int,y:Int,r:Int):Boolean{for(dy in -r..r)for(dx in -r..r){if(dx==0&&dy==0)continue;val xx=x+dx;val yy=y+dy;if(xx in 0 until w&&yy in 0 until h&&mask[yy*w+xx])return true};return false}
    private fun resizeMask(mask:BooleanArray,w:Int,h:Int,nw:Int,nh:Int):BooleanArray{val out=BooleanArray(nw*nh);for(y in 0 until nh){val sy=(y*h/nh).coerceIn(0,h-1);for(x in 0 until nw){val sx=(x*w/nw).coerceIn(0,w-1);out[y*nw+x]=mask[sy*w+sx]}};return out}
}

/** ONNX image-to-line model runner. Models are cached privately after first download. */
private class AiLineartEngine private constructor(private val context: Context) {
    companion object {
        @Volatile private var instance: AiLineartEngine? = null
        fun get(context: Context): AiLineartEngine = instance ?: synchronized(this) { instance ?: AiLineartEngine(context.applicationContext).also { instance=it } }
        private const val DRAW_URL="https://huggingface.co/Luoaho/image-to-line-drawing-onnx/resolve/main/line-drawings.onnx?download=true"
        private const val RELIEF_URL="https://huggingface.co/Luoaho/image-to-line-drawing-onnx/resolve/main/line-relifer.onnx?download=true"
    }
    private val dir=File(context.filesDir,"ai_models").apply{mkdirs()}
    private var env:OrtEnvironment?=null
    private var draw:OrtSession?=null
    private var relief:OrtSession?=null
    private var initialized=false

    fun isReady():Boolean{
        if(initialized)return draw!=null
        initialized=true
        val d=File(dir,"line-drawings.onnx");val r=File(dir,"line-relifer.onnx")
        if(!d.exists()||!r.exists()){
            download(DRAW_URL,d);download(RELIEF_URL,r)
        }
        if(!d.exists()||!r.exists())return false
        return runCatching{env=OrtEnvironment.getEnvironment();val opts=OrtSession.SessionOptions();opts.setIntraOpNumThreads(4);draw=env!!.createSession(d.absolutePath,opts);relief=env!!.createSession(r.absolutePath,opts);true}.getOrDefault(false)
    }

    fun infer(bitmap:Bitmap,size:Int,enhance:Boolean):Pair<BooleanArray,Pair<Int,Int>>{
        val d=draw?:error("AI model unavailable");val r=relief?:error("AI model unavailable");val input=letterbox(bitmap,size)
        val data=FloatArray(size*size*3);val px=IntArray(size*size);input.getPixels(px,0,size,0,0,size,size)
        var k=0;for(c in 0..2)for(i in px.indices){val p=px[i];data[k++]=when(c){0->Color.red(p);1->Color.green(p);else->Color.blue(p)}/255f}
        val tensor=OnnxTensor.createTensor(env,FloatBuffer.wrap(data),longArrayOf(1,3,size.toLong(),size.toLong()))
        val res=d.run(mapOf(d.inputNames.first() to tensor));val t=res[0] as OnnxTensor;val fb=t.floatBuffer;val n=size*size;val raw=FloatArray(n);fb.get(raw,0,n);tensor.close();res.close()
        val enhanced=if(enhance){val nhwc=FloatArray(n);for(i in 0 until n)nhwc[i]=raw[i]*2f-1f;val rt=OnnxTensor.createTensor(env,FloatBuffer.wrap(nhwc),longArrayOf(1,size.toLong(),size.toLong(),1));val rr=r.run(mapOf(r.inputNames.first() to rt));val ot=rr[0] as OnnxTensor;val of=ot.floatBuffer;val arr=FloatArray(n);of.get(arr,0,n);ot.close();rr.close();rt.close();arr}else raw
        val minV=enhanced.minOrNull()?:0f;val maxV=enhanced.maxOrNull()?:1f;val span=(maxV-minV).takeIf{it>1e-6f}?:1f
        val mask=BooleanArray(n){((enhanced[it]-minV)/span)<0.46f}
        input.recycle()
        return mask to (size to size)
    }

    private fun letterbox(src:Bitmap,size:Int):Bitmap{val out=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888);val c=android.graphics.Canvas(out);c.drawColor(Color.WHITE);val scale=min(size.toFloat()/src.width,size.toFloat()/src.height);val w=(src.width*scale).toInt();val h=(src.height*scale).toInt();val left=(size-w)/2f;val top=(size-h)/2f;val scaled=Bitmap.createScaledBitmap(src,w,h,true);c.drawBitmap(scaled,left,top,null);if(scaled!==src)scaled.recycle();return out}
    private fun download(url:String,file:File){runCatching{val tmp=File(file.parentFile,file.name+".part");val con=(URL(url).openConnection() as HttpURLConnection).apply{connectTimeout=20000;readTimeout=120000;requestMethod="GET";setRequestProperty("User-Agent","StencilPro/1.0")};con.connect();if(con.responseCode in 200..299){con.inputStream.use{input->FileOutputStream(tmp).use{out->input.copyTo(out)}};if(tmp.length()>100_000)tmp.renameTo(file)};con.disconnect()}}
}
