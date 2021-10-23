package bobby;

import java.net.*;
import java.io.*;
import java.util.*;

import java.util.concurrent.Semaphore;


import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class ScotlandYard implements Runnable{

	/*
		this is a wrapper class for the game.
		It just loops, and runs game after game
	*/

	public int port;
	public int gamenumber;

	public ScotlandYard(int port){
		this.port = port;
		this.gamenumber = 0;
	}

	public void run(){
		while (true){
			Thread tau = new Thread(new ScotlandYardGame(this.port, this.gamenumber));
			tau.start();
			try{
				tau.join();
			}
			catch (InterruptedException e){
				return;
			}
			this.gamenumber++;
		}
	}

	public class ScotlandYardGame implements Runnable{
		private Board board;
		private ServerSocket server;
		public int port;
		public int gamenumber;
		private ExecutorService threadPool;
		public int count_detectives;

		public ScotlandYardGame(int port, int gamenumber){
			this.port = port;
			this.board = new Board();
			this.gamenumber = gamenumber;
			this.count_detectives=0;
			try{
				this.server = new ServerSocket(port);
				System.out.println("New ServerSocket was formed correctly");
				System.out.println(String.format("Game %d:%d on", port, gamenumber));
				server.setSoTimeout(5000);
			}
			catch (IOException i) {
				System.out.println("New ServerSocket was not formed");
				return;
			}
			this.threadPool = Executors.newFixedThreadPool(10);
		}


		public void run(){

			try{
			
				//INITIALISATION: get the game going

				Socket socket = null;
				boolean fugitiveIn;
				
				/*
				listen for a client to play fugitive, and spawn the moderator.
				
				here, it is actually ok to edit this.board.dead, because the game hasn't begun
				*/
				
				do{
					try{
						socket = server.accept();
						fugitiveIn=true;
					}
					catch(SocketTimeoutException t){
						fugitiveIn=false;
					}
					catch(Exception e){
						// System.out.println("Nice");
						fugitiveIn=false;
					}
				} while (!fugitiveIn);

				this.board.dead=false;

				System.out.println(this.gamenumber);

				// Spawn a thread to run the Fugitive
                board.totalThreads++;
				Runnable fugitive_thread = new ServerThread(board, -1 , socket, port, gamenumber);
				threadPool.execute(fugitive_thread);

				

				// Runnable moderator_thread = new Moderator(board);
				// threadPool.execute(moderator_thread);

				Moderator moderator=new Moderator(board);
				Thread moderator_thread=new Thread(moderator);
				moderator_thread.start();

				System.out.println("Moderator must start here");

				// Spawn the moderator
                // If code doesn't works check for the commented part of reentry permits
				// in ServerThread.java 
				// ~Hastyn 23/10/2021                              
                
				while (true){
					/*
					listen on the server, accept connections
					if there is a timeout, check that the game is still going on, and then listen again!
					*/

					try {
						// if(this.count_detectives==5){
						// 	break;
						// }
						socket = server.accept();
					} 
					catch (SocketTimeoutException t){
						board.threadInfoProtector.acquire();
						if(this.board.dead==true){
							System.out.println("Line 139 ScotlandYard.java, threadinfo released");
							board.threadInfoProtector.release();
							break;
						}
						board.threadInfoProtector.release();
						continue;
					}
					// this.count_detectives++;
					
					
					/*
					acquire thread info lock, and decide whether you can serve the connection at this moment,

					if you can't, drop connection (game full, game dead), continue, or break.

					if you can, spawn a thread, assign an ID, increment the totalThreads

					don't forget to release lock when done!
					*/ 
					board.threadInfoProtector.acquire();
					if(board.playingThreads>=6){
						socket.close();
						board.threadInfoProtector.release();

						// If the server/socket gives error consider debugging this line :(
						// ~Shikhar 23/10/2021

						continue;
					}
					if(board.dead==true){
						socket.close(); //Needs to be looked
						board.threadInfoProtector.release();
						break;
					}
					
					Runnable detective_thread = new ServerThread(board, board.getAvailableID() , socket, port, gamenumber);
					threadPool.execute(detective_thread);
					board.totalThreads++;
					System.out.println("Detective serverthread spawned");
					board.threadInfoProtector.release();
				}

				/*
				reap the moderator thread, close the server, 
				
				kill threadPool (Careless Whispers BGM stops)
				*/

				try{
					moderator_thread.interrupt();
				}
				catch(Exception e){
					System.out.println("Nice was caught");
				}
				socket.close();
				System.out.println("The socket was closed successfully");
				threadPool.shutdown();
				System.out.println("Threadpool was shutdown successfully");
				System.out.println(String.format("Game %d:%d Over", this.port, this.gamenumber));
				server.close();
				return;
			}
			catch (InterruptedException ex){
				System.out.println("Line 194 of ScotlandYard.java");
				System.err.println("An InterruptedException was caught: " + ex.getMessage());
				ex.printStackTrace();
				return;
			}
			catch (IOException i){
				return;
			}
			
		}

		
	}

	public static void main(String[] args) {
		for (int i=0; i<args.length; i++){
			int port = Integer.parseInt(args[i]);
			Thread tau = new Thread(new ScotlandYard(port));
			tau.start();
		}
	}
}