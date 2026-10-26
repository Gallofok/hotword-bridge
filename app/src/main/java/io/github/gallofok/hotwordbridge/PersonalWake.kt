package io.github.gallofok.hotwordbridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.*

/** On-device PERSONAL ACOUSTIC TEMPLATES, not a neural network.
 *  Short-window log spectral features + DTW, negative sample gate.
 *  Experimental: NOT a security/authentication feature.
 */
object PersonalWake {
    data class Prompt(val phrase:String, val hint:String, val group:String, val heldOut:Boolean=false)
    val prompts =
        List(6) { Prompt("Hey Chat G P T", "正常语速，字母分开", "positive") } +
        List(6) { Prompt("Hey ChatGPT", "快速自然连读", "positive") } +
        List(2) { Prompt("Hey ChatGPT", "远一点、轻声", "positive") } +
        listOf(
            Prompt("Hey Google", "负例：相似唤醒", "negative"),
            Prompt("Hey Check GPS", "负例：相似发音", "negative"),
            Prompt("Hello World", "负例：其他短语", "negative"),
            Prompt("I want to check something", "负例：普通说话", "negative"),
            Prompt("Hey Chat G P T", "未参与训练的正常语速测试", "positive", true),
            Prompt("Hey ChatGPT", "未参与训练的快速连读测试", "positive", true),
            Prompt("Hey Google", "未参与训练的反例", "negative", true),
            Prompt("Hello World", "未参与训练的反例", "negative", true)
        )
    data class Model(val threshold:Float, val pos:List<Array<FloatArray>>,
                     val neg:List<Array<FloatArray>>, val hit:Int, val goodTotal:Int,
                     val falseHits:Int, val negTotal:Int)
    data class Score(val match:Boolean, val positive:Float, val negative:Float)

    private const val RATE=16000
    private const val FRAME=320
    private const val HOP=160
    private const val BANDS=18
    private val main=Handler(Looper.getMainLooper())
    private fun folder(c:Context)=File(c.filesDir, "personal_wake")
    private fun sample(c:Context, i:Int)=File(folder(c), "sample-$i.pcm")
    private fun trained(c:Context)=File(folder(c), "acoustic-v1.dat")
    fun count(c:Context):Int=prompts.indices.count { sample(c,it).length()>=RATE*2L }
    fun next(c:Context):Int=prompts.indices.firstOrNull { sample(c,it).length()<RATE*2L } ?: prompts.size
    fun ready(c:Context):Boolean=trained(c).isFile
    fun erase(c:Context) { folder(c).deleteRecursively() }
    fun remove(c:Context,i:Int) { if (i in prompts.indices) sample(c,i).delete(); trained(c).delete() }

    fun record(context:Context,index:Int,callback:(Boolean,String)->Unit) {
        val ctx=context.applicationContext
        if(index !in prompts.indices) { callback(false,"录制已完成"); return }
        if(ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=
            PackageManager.PERMISSION_GRANTED) { callback(false,"请先允许麦克风权限"); return }
        thread(name="personal-kws-record") {
            var mic:AudioRecord?=null
            var msg=""
            var ok=false
            try {
                val minBytes=AudioRecord.getMinBufferSize(RATE,AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT)
                check(minBytes>0) { "录音缓冲区不可用" }
                mic=AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,RATE,
                    AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,
                    max(minBytes*2,6400))
                check(mic.state==AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
                mic.startRecording()
                val file=sample(ctx,index)
                file.parentFile?.mkdirs()
                val temp=File(file.parentFile,"recording.tmp")
                var n=0
                var peak=0
                val buf=ShortArray(1600)
                temp.outputStream().buffered().use { out ->
                    while(n<RATE*33/10) {
                        val r=mic.read(buf,0,min(buf.size,RATE*33/10-n))
                        check(r>0) { "录音没有收到音频" }
                        for(i in 0 until r) {
                            val v=buf[i].toInt()
                            peak=max(peak,abs(v))
                            out.write(v and 255)
                            out.write((v shr 8) and 255)
                        }
                        n+=r
                    }
                }
                check(peak>120) { "录音几乎静音，请重试" }
                check(temp.renameTo(file)) { "保存录音失败" }
                trained(ctx).delete()
                ok=true
                msg="保存成功，第 "+(index+1)+"/"+prompts.size+" 条；3.3秒，数据仅存在本机"
            } catch(t:Throwable) { msg="录制失败："+(t.message ?: t.javaClass.simpleName) }
            finally {
                try { mic?.stop() } catch(_:Throwable) {}
                try { mic?.release() } catch(_:Throwable) {}
                val s=ok
                val m=msg
                main.post { callback(s,m) }
            }
        }
    }

    private fun read(file:File):ShortArray {
        val b=file.readBytes()
        require(b.size>=RATE*2 && b.size%2==0) { "PCM 样本文件缺失或损坏" }
        return ShortArray(b.size/2) { i ->
            (((b[i*2+1].toInt() and 255) shl 8) or
                (b[i*2].toInt() and 255)).toShort()
        }
    }

    fun train(context:Context,callback:(Boolean,String)->Unit) {
        val c=context.applicationContext
        if(next(c)<prompts.size) {
            callback(false,"请先录满 "+prompts.size+" 条样本"); return
        }
        thread(name="personal-kws-training") {
            var success=false
            var message=""
            try {
                val data=prompts.indices.map { features(read(sample(c,it))) }
                val positives=data.indices.filter {
                    prompts[it].group=="positive" && !prompts[it].heldOut
                }.map { data[it] }
                val negatives=data.indices.filter {
                    prompts[it].group=="negative" && !prompts[it].heldOut
                }.map { data[it] }
                val leaveOneOut=positives.indices.map { i ->
                    positives.indices.filter { it!=i }.minOf { j ->
                        dtw(positives[i],positives[j])
                    }
                }.sorted()
                val p90=leaveOneOut[(leaveOneOut.size*0.9).toInt().coerceAtMost(leaveOneOut.lastIndex)]
                val hardNeg=negatives.minOf { neg -> positives.minOf { dtw(neg,it) } }
                val threshold=min(p90*1.18f+0.04f,hardNeg*0.92f).coerceAtLeast(0.09f)
                val base=Model(threshold,positives,negatives,0,0,0,0)
                var hits=0
                var total=0
                var falseHits=0
                var negTotal=0
                for(i in data.indices) {
                    if(!prompts[i].heldOut) continue
                    val triggered=score(base,data[i]).match
                    if(prompts[i].group=="positive") {
                        total++
                        if(triggered) hits++
                    } else {
                        negTotal++
                        if(triggered) falseHits++
                    }
                }
                save(c,base.copy(hit=hits,goodTotal=total,
                    falseHits=falseHits,negTotal=negTotal))
                success=true
                message="训练完成：14 条正例 / 4 条负例。独立验证唤醒 "+
                    hits+"/"+total+"，误唤醒 "+falseHits+"/"+negTotal+
                    "。匹配阈值 "+String.format(java.util.Locale.US,"%.2f",threshold)+
                    "。这只是个人声学模板，仍需现实环境测试。"
            } catch(t:Throwable) { message="训练失败："+(t.message ?: t.javaClass.simpleName) }
            val s=success; val m=message
            main.post { callback(s,m) }
        }
    }

    private fun save(c:Context,m:Model) {
        folder(c).mkdirs()
        val tmp=File(folder(c),"model.tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { out ->
            out.writeUTF("HWB-PERSONAL-1")
            out.writeFloat(m.threshold)
            out.writeInt(m.hit);out.writeInt(m.goodTotal)
            out.writeInt(m.falseHits);out.writeInt(m.negTotal)
            for(group in listOf(m.pos,m.neg)) {
                out.writeInt(group.size)
                for(example in group) {
                    out.writeInt(example.size)
                    for(frame in example) for(value in frame) out.writeFloat(value)
                }
            }
        }
        check(tmp.renameTo(trained(c))) { "无法写入模型文件" }
    }
    fun load(c:Context):Model {
        DataInputStream(trained(c).inputStream().buffered()).use { input ->
            check(input.readUTF()=="HWB-PERSONAL-1") { "模型格式不兼容" }
            val threshold=input.readFloat()
            val hit=input.readInt();val total=input.readInt()
            val fp=input.readInt();val negTotal=input.readInt()
            fun group():List<Array<FloatArray>> {
                val size=input.readInt()
                require(size in 1..32)
                return List(size) {
                    val frames=input.readInt()
                    require(frames in 20..400)
                    Array(frames) { FloatArray(BANDS) { input.readFloat() } }
                }
            }
            return Model(threshold,group(),group(),hit,total,fp,negTotal)
        }
    }
    fun score(m:Model, f:Array<FloatArray>):Score {
        val p=m.pos.minOf { dtw(it,f) }
        val n=m.neg.minOf { dtw(it,f) }
        return Score(p<=m.threshold && n-p>0.04f,p,n)
    }
    fun test(m:Model, pcm:ShortArray):Score=score(m,features(pcm))

    /** Energy trimming, log spectral Goertzel features and per-file whitening. */
    private fun features(input:ShortArray):Array<FloatArray> {
        require(input.size>=6400) { "说话时间太短" }
        val count=(input.size-FRAME)/HOP+1
        val rms=DoubleArray(count) { frame ->
            var sum=0.0
            for(k in 0 until FRAME) {
                val v=input[frame*HOP+k].toDouble()
                sum+=v*v
            }
            sqrt(sum/FRAME)
        }
        val peak=rms.maxOrNull() ?: 0.0
        require(peak>130) { "录音过于安静" }
        val gate=max(110.0,peak*0.11)
        val active=rms.indices.filter { rms[it]>gate }
        require(active.size>=12) { "没有检测到足够的语音" }
        val start=max(0,active.first()-8)
        val stop=min(count-1,active.last()+8)
        val output=Array(stop-start+1) { FloatArray(BANDS) }
        for(frame in start..stop) {
            val begin=frame*HOP
            for(b in 0 until BANDS) {
                // Log-spaced frequencies, ~180-3300 Hz.
                val hz=180.0*(3300.0/180.0).pow(b.toDouble()/(BANDS-1))
                val omega=2*PI*hz/RATE
                val coeff=2*cos(omega)
                var a=0.0
                var b1=0.0
                for(k in 0 until FRAME) {
                    val taper=0.54-0.46*cos(2*PI*k/(FRAME-1))
                    val next=(input[begin+k]/32768.0)*taper+coeff*a-b1
                    b1=a
                    a=next
                }
                val power=max(1e-9,a*a+b1*b1-coeff*a*b1)
                output[frame-start][b]=ln(power).toFloat()
            }
        }
        for(b in 0 until BANDS) {
            val mean=output.sumOf { it[b].toDouble() }/output.size
            val variance=output.sumOf { (it[b]-mean).toDouble().pow(2) }/output.size
            val sd=sqrt(variance).coerceAtLeast(0.2)
            output.forEach { it[b]=((it[b]-mean)/sd).toFloat() }
        }
        return output
    }

    private fun dtw(a:Array<FloatArray>,b:Array<FloatArray>):Float {
        val n=a.size;val m=b.size
        val width=max(14,(m*0.42).toInt())
        val prev=FloatArray(m+1) { Float.POSITIVE_INFINITY }
        val cur=FloatArray(m+1) { Float.POSITIVE_INFINITY }
        prev[0]=0f
        for(i in 1..n) {
            java.util.Arrays.fill(cur,Float.POSITIVE_INFINITY)
            val center=i.toDouble()*m/n
            val low=max(1,floor(center-width).toInt())
            val high=min(m,ceil(center+width).toInt())
            for(j in low..high) {
                var cost=0f
                for(k in 0 until BANDS) {
                    val d=a[i-1][k]-b[j-1][k]
                    cost+=d*d
                }
                cur[j]=cost/BANDS+minOf(prev[j],cur[j-1],prev[j-1])
            }
            System.arraycopy(cur,0,prev,0,m+1)
        }
        return sqrt(prev[m]/max(n,m))
    }
}
