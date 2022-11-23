package twim.melsecplc.Melsec;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.CharsetUtil;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import twim.melsecplc.codec.ClientFrame3EAsciiMessageDecoder;
import twim.melsecplc.codec.ClientFrameEMessageEncoder;
import twim.melsecplc.codec.Frame3EAsciiByteDecoder;
import twim.melsecplc.core.MelsecClientOptions;
import twim.melsecplc.core.message.Function;
import twim.melsecplc.core.message.e.Frame3EAsciiCommand;
import twim.melsecplc.core.message.e.FrameECommand;
import twim.melsecplc.core.message.e.FrameEResponse;

import java.nio.charset.Charset;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

@Getter
public class MelsecPlcHandler {
    
    private static final Logger log = LoggerFactory.getLogger(MelsecPlcHandler.class);
    private Thread plcThread;
    private final NioEventLoopGroup workerGroup = new NioEventLoopGroup();;
    private Channel channel;

    private final MelsecClientConfig melsecClientConfig;
    private final Queue<FrameECommand> requestQueue = new LinkedList<>();
    private final Queue<FrameEResponse> responseQueue = new LinkedList<>();

    private final Lock lock = new ReentrantLock();

    public MelsecPlcHandler(String ip, int port){
        
        this.melsecClientConfig = MelsecClientConfig.builder().address(ip).port(port).build();

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(this.workerGroup)
                    .channel(NioSocketChannel.class)
                    .remoteAddress(this.melsecClientConfig.getAddress(), this.melsecClientConfig.getPort())
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1500)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .option(ChannelOption.SO_LINGER, 0)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .handler(new ChannelInitializer<SocketChannel>(){

                        @Override
                        public void initChannel(SocketChannel ch) throws Exception {

                            ChannelPipeline pipeline = ch.pipeline();
                            // handler setting
                            pipeline.addLast(ClientFrameEMessageEncoder.INSTANCE);
                            pipeline.addLast(new ClientFrame3EAsciiMessageDecoder());
                            pipeline.addLast(new Frame3EAsciiByteDecoder());
                            pipeline.addLast(new NettySocketClientHandler(MelsecPlcHandler.this));
                        }
                    });

        this.plcThread = new Thread(() -> {

            log.info("Attempt connect to PLC({})...", ip);

            connect(bootstrap);

            log.info("Connected to PLC({})...", ip);
            //log.info(batchRead("D45010", 1).get());

            while (true){
                try {
                    batchRead("D45000", 2).get();

                    // TODO: 2022-11-22 PLC의 D45000 값을 몇초에 한번 씩 변경할지에 따라 정하기
                    Thread.sleep(1500);


                    if (!isConnected()){
                        connect(bootstrap);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, "plc-" + ip);

        this.plcThread.setDaemon(true);
        this.plcThread.start();
    }

    public void connect(Bootstrap bootstrap){

        try {
            ChannelFuture f = bootstrap.connect().sync();
            this.channel = f.channel();
        } catch (InterruptedException e) {
            log.error(e.getMessage());
        } catch (Exception e) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ee) {
                log.warn("connect " + ee.getMessage());
            }

            connect(bootstrap);
        }
    }

    public void remove(){
        if (this.plcThread != null && !this.plcThread.isInterrupted())
            this.plcThread.interrupt();
        this.workerGroup.shutdownGracefully().awaitUninterruptibly();
    }

    public boolean isConnected(){

        if (this.channel == null)
            return false;
        else
            return this.channel.isActive();
    }

    public CompletableFuture<String> batchRead(String address, int points){

        return requestAPI(new Frame3EAsciiCommand(
            Function.BATCH_READ,
            address,
            points,
            new MelsecClientOptions(this.melsecClientConfig.getNetworkNo(),
                this.melsecClientConfig.getPcNo(),
                this.melsecClientConfig.getRequestDestinationModuleIoNo(),
                this.melsecClientConfig.getRequestDestinationModuleStationNo())));
    }

    public CompletableFuture<String> batchWrite(String address, int points, ByteBuf data){

        return requestAPI(new Frame3EAsciiCommand(
            Function.BATCH_WRITE,
            address,
            points,
            data,
            new MelsecClientOptions(this.melsecClientConfig.getNetworkNo(),
                this.melsecClientConfig.getPcNo(),
                this.melsecClientConfig.getRequestDestinationModuleIoNo(),
                this.melsecClientConfig.getRequestDestinationModuleStationNo())))
            .thenCompose(r -> batchRead(address, points));
    }

    private CompletableFuture<String> requestAPI(FrameECommand command){
        
        Supplier<String> responseSupplier = () -> {
            
            this.lock.lock();

            long start = System.currentTimeMillis();
            this.channel.writeAndFlush(command).addListener(listener -> {
                if (!listener.isSuccess())
                    log.info(this.melsecClientConfig.getAddress() + "- Request failed: " + command.getPrincipal().getAddress()
                            + ", Thread- " + Thread.currentThread().getName());
                else
                    this.requestQueue.add(command);
            });

            try {
                while (true){
                    if (this.responseQueue.size() > 0){
                        log.info(this.melsecClientConfig.getAddress() + "- Tact time: " + (System.currentTimeMillis() - start)
                                + ", Thread- " + Thread.currentThread().getName());

                        FrameEResponse response = this.responseQueue.poll();

                        if (response.getData() == null)
                            return "";
                        else
                            return ByteBufUtil.hexDump(response.getData());
                    }

                    // TODO: 2022-11-22 TIME OUT 필요없을 시 제거
                    if ((System.currentTimeMillis() - start) > 1)
                        return "TIMEOUT";

                    Thread.sleep(1);
                }
            } catch (InterruptedException e) {
                return "Error: " + e.getMessage();
            } finally {
                this.lock.unlock();
            }
        };

        return CompletableFuture.supplyAsync(responseSupplier);
    }

    /**
     * PLC 에 명령 전달
     * todo: 추후에 data format 맞춰서 보내기
     */
    public void sendCommand(){

        ByteBuf data = Unpooled.buffer();
        data.writeBoolean(false);
        data.writeBoolean(true);
        data.writeBoolean(false);
        data.writeBoolean(false);
        data.writeBoolean(true);
        data.writeBoolean(false);
        data.writeBoolean(true);
        data.writeBoolean(true);

        log.info("Send Data ByteBuf: " + data);

        try {
            ChannelFuture cf = channel.writeAndFlush(data);
            cf.addListener((ChannelFutureListener) channelFuture -> {
                if(channelFuture.isSuccess()){
                    log.info("write success");
                    log.info(batchWrite("D45500", 1, data).get());
                } else {
                    log.error("write error");
                    channelFuture.cause().printStackTrace();
                }
            });
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }
}